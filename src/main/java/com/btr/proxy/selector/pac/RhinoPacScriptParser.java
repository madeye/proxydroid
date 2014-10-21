package com.btr.proxy.selector.pac;

import java.util.Calendar;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import android.util.Log;

/*****************************************************************************
 * PAC parser using the Rhino JavaScript engine.<br/>
 * Depends on js.jar of the <a href="http://www.mozilla.org/rhino/">Apache Rhino
 * </a> project.
 * <p>
 * More information about PAC can be found there:<br/>
 * <a
 * href="http://en.wikipedia.org/wiki/Proxy_auto-config">Proxy_auto-config</a><br/>
 * <a href=
 * "http://homepages.tesco.net/~J.deBoynePollard/FGA/web-browser-auto-proxy-configuration.html"
 * >web-browser-auto-proxy-configuration</a>
 * </p>
 * 
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 ****************************************************************************/

public class RhinoPacScriptParser extends ScriptableObject implements
		PacScriptParser {

	private static final long serialVersionUID = 1L;

	private final static String TAG = "ProxyDroid.PAC";

	// Define some PAC script functions. These functions are not part of ECMA.
	private static final String[] JS_FUNCTION_NAMES = { "shExpMatch",
			"dnsResolve", "isResolvable", "isInNet", "dnsDomainIs",
			"isPlainHostName", "myIpAddress", "dnsDomainLevels",
			"localHostOrDomainIs", "weekdayRange", "dateRange", "timeRange" };

	private Scriptable scope;
	private PacScriptSource source;
	private static final PacScriptMethods SCRIPT_METHODS = new PacScriptMethods();

	/*************************************************************************
	 * Constructor
	 * 
	 * @param source
	 *            the source for the PAC script.
	 * @throws ProxyEvaluationException
	 *             on error.
	 ************************************************************************/

	public RhinoPacScriptParser(PacScriptSource source)
			throws ProxyEvaluationException {
		super();
		this.source = source;

		setupEngine();
	}

	/*************************************************************************
	 * Initializes the JavaScript engine.
	 * 
	 * @throws ProxyEvaluationException
	 *             on error.
	 ************************************************************************/

	public void setupEngine() throws ProxyEvaluationException {

		Context context = new ContextFactory().enterContext();
		try {
			defineFunctionProperties(JS_FUNCTION_NAMES,
					RhinoPacScriptParser.class, ScriptableObject.DONTENUM);
		} catch (Exception e) {
			Log.e(TAG, "JS Engine setup error.", e);
			throw new ProxyEvaluationException(e.getMessage(), e);
		}

		this.scope = context.initStandardObjects(this);
	}

	/***************************************************************************
	 * Gets the source of the PAC script used by this parser.
	 * 
	 * @return a PacScriptSource.
	 **************************************************************************/

	@Override
	public PacScriptSource getScriptSource() {
		return this.source;
	}

	/*************************************************************************
	 * Evaluates the given URL and host against the PAC script.
	 * 
	 * @param url
	 *            the URL to evaluate.
	 * @param host
	 *            the host name part of the URL.
	 * @return the script result.
	 * @throws ProxyEvaluationException
	 *             on execution error.
	 ************************************************************************/

	@Override
	public String evaluate(String url, String host)
			throws ProxyEvaluationException {
		try {
			// FindProxyForURL function signature
			StringBuilder script = new StringBuilder(
					this.source.getScriptContent());
			String evalMethod = " ;FindProxyForURL (\"" + url + "\",\"" + host
					+ "\")";
			script.append(evalMethod);

			Context context = Context.enter();
			context.setOptimizationLevel(-1);
			try {
				Object result = context.evaluateString(this.scope,
						script.toString(), "userPacFile", 1, null);

				return Context.toString(result);
			} finally {
				Context.exit();
			}
		} catch (Exception e) {
			Log.e(TAG, "JS evaluation error.", e);
			throw new ProxyEvaluationException(
					"Error while executing PAC script: " + e.getMessage(), e);
		}
	}

	/*************************************************************************
	 * getClassName See also
	 * org.mozilla.javascript.ScriptableObject#getClassName()
	 ************************************************************************/
	@Override
	public String getClassName() {
		return getClass().getSimpleName();
	}

	// ***************************************************************************
	// Defining PAC script methods needed in JS
	// ***************************************************************************

	/*************************************************************************
	 * Tests if the given name is a plain host name without a domain name.
	 * 
	 * @param host
	 *            the host name from the URL (excluding port number)
	 * @return true if there is no domain name in the host name (no dots).
	 ************************************************************************/

	public static boolean isPlainHostName(String host) {
		return SCRIPT_METHODS.isPlainHostName(host);
	}

	/*************************************************************************
	 * Tests if an URL is in a given domain.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @param domain
	 *            is the domain name to test the host name against.
	 * @return true if the domain of host name matches.
	 ************************************************************************/

	public static boolean dnsDomainIs(String host, String domain) {
		return SCRIPT_METHODS.dnsDomainIs(host, domain);
	}

	/*************************************************************************
	 * Is true if the host name matches exactly the specified host name, or if
	 * there is no domain name part in the host name, but the unqualified host
	 * name matches.
	 * 
	 * @param host
	 *            the host name from the URL.
	 * @param domain
	 *            fully qualified host name with domain to match against.
	 * @return true if matches else false.
	 ************************************************************************/

	public static boolean localHostOrDomainIs(String host, String domain) {
		return SCRIPT_METHODS.localHostOrDomainIs(host, domain);
	}

	/*************************************************************************
	 * Tries to resolve the host name. Returns true if succeeds.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @return true if resolvable else false.
	 ************************************************************************/

	public static boolean isResolvable(String host) {
		return SCRIPT_METHODS.isResolvable(host);
	}

	/*************************************************************************
	 * Returns true if the IP address of the host matches the specified IP
	 * address pattern. Pattern and mask specification is done the same way as
	 * for SOCKS configuration.
	 * 
	 * Example: isInNet(host, "198.95.0.0", "255.255.0.0") is true if the IP
	 * address of the host matches 198.95.*.*.
	 * 
	 * @param host
	 *            a DNS host name, or IP address. If a host name is passed, it
	 *            will be resolved into an IP address by this function.
	 * @param pattern
	 *            an IP address pattern in the dot-separated format.
	 * @param mask
	 *            mask for the IP address pattern informing which parts of the
	 *            IP address should be matched against. 0 means ignore, 255
	 *            means match.
	 * @return true if it matches else false.
	 ************************************************************************/

	public static boolean isInNet(String host, String pattern, String mask) {
		return SCRIPT_METHODS.isInNet(host, pattern, mask);
	}

	/*************************************************************************
	 * Resolves the given DNS host name into an IP address, and returns it in
	 * the dot separated format as a string.
	 * 
	 * @param host
	 *            the host to resolve.
	 * @return the resolved IP, empty string if not resolvable.
	 ************************************************************************/

	public static String dnsResolve(String host) {
		return SCRIPT_METHODS.dnsResolve(host);
	}

	/*************************************************************************
	 * Returns the IP address of the host that the process is running on, as a
	 * string in the dot-separated integer format.
	 * 
	 * @return an IP as string.
	 ************************************************************************/

	public static String myIpAddress() {
		return SCRIPT_METHODS.myIpAddress();
	}

	/*************************************************************************
	 * Returns the number of DNS domain levels (number of dots) in the host
	 * name.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @return number of DNS domain levels.
	 ************************************************************************/

	public static int dnsDomainLevels(String host) {
		return SCRIPT_METHODS.dnsDomainLevels(host);
	}

	/*************************************************************************
	 * Returns true if the string matches the specified shell expression.
	 * Actually, currently the patterns are shell expressions, not regular
	 * expressions.
	 * 
	 * @param str
	 *            is any string to compare (e.g. the URL, or the host name).
	 * @param shexp
	 *            is a shell expression to compare against.
	 * @return true if the string matches, else false.
	 ************************************************************************/

	public static boolean shExpMatch(String str, String shexp) {
		return SCRIPT_METHODS.shExpMatch(str, shexp);
	}

	/*************************************************************************
	 * Only the first parameter is mandatory. Either the second, the third, or
	 * both may be left out. If only one parameter is present, the function
	 * yields a true value on the weekday that the parameter represents. If the
	 * string "GMT" is specified as a second parameter, times are taken to be in
	 * GMT, otherwise in local time zone. If both wd1 and wd2 are defined, the
	 * condition is true if the current weekday is in between those two
	 * weekdays. Bounds are inclusive. If the "GMT" parameter is specified,
	 * times are taken to be in GMT, otherwise the local time zone is used.
	 * 
	 * @param wd1
	 *            weekday 1 is one of SUN MON TUE WED THU FRI SAT
	 * @param wd2
	 *            weekday 2 is one of SUN MON TUE WED THU FRI SAT
	 * @param gmt
	 *            "GMT" for gmt time format else "undefined"
	 * @return true if current day matches the criteria.
	 ************************************************************************/

	public static boolean weekdayRange(String wd1, String wd2, String gmt) {
		return SCRIPT_METHODS.weekdayRange(wd1, wd2, gmt);
	}

	/*************************************************************************
	 * Sets a calendar with the current time. If this is set all date and time
	 * based methods will use this calendar to determine the current time
	 * instead of the real time. This is only be used by unit tests and is not
	 * part of the public API.
	 * 
	 * @param cal
	 *            a Calendar to set.
	 ************************************************************************/

	static void setCurrentTime(Calendar cal) {
		SCRIPT_METHODS.setCurrentTime(cal);
	}

	/*************************************************************************
	 * Only the first parameter is mandatory. All other parameters can be left
	 * out therefore the meaning of the parameters changes. The method
	 * definition shows the version with the most possible parameters filled.
	 * The real meaning of the parameters is guessed from it's value. If "from"
	 * and "to" are specified then the bounds are inclusive. If the "GMT"
	 * parameter is specified, times are taken to be in GMT, otherwise the local
	 * time zone is used.
	 * 
	 * @param day1
	 *            is the day of month between 1 and 31 (as an integer).
	 * @param month1
	 *            one of JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC
	 * @param year1
	 *            is the full year number, for example 1995 (but not 95).
	 *            Integer.
	 * @param day2
	 *            is the day of month between 1 and 31 (as an integer).
	 * @param month2
	 *            one of JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC
	 * @param year2
	 *            is the full year number, for example 1995 (but not 95).
	 *            Integer.
	 * @param gmt
	 *            "GMT" for gmt time format else "undefined"
	 * @return true if the current date matches the given range.
	 ************************************************************************/

	public static boolean dateRange(Object day1, Object month1, Object year1,
			Object day2, Object month2, Object year2, Object gmt) {
		return SCRIPT_METHODS.dateRange(day1, month1, year1, day2, month2,
				year2, gmt);
	}

	/*************************************************************************
	 * Some parameters can be left out therefore the meaning of the parameters
	 * changes. The method definition shows the version with the most possible
	 * parameters filled. The real meaning of the parameters is guessed from
	 * it's value. If "from" and "to" are specified then the bounds are
	 * inclusive. If the "GMT" parameter is specified, times are taken to be in
	 * GMT, otherwise the local time zone is used.<br/>
	 * 
	 * <pre>
	 * timeRange(hour)
	 * timeRange(hour1, hour2)
	 * timeRange(hour1, min1, hour2, min2)
	 * timeRange(hour1, min1, sec1, hour2, min2, sec2)
	 * timeRange(hour1, min1, sec1, hour2, min2, sec2, gmt)
	 * </pre>
	 * 
	 * @param hour1
	 *            is the hour from 0 to 23. (0 is midnight, 23 is 11 pm.)
	 * @param min1
	 *            minutes from 0 to 59.
	 * @param sec1
	 *            seconds from 0 to 59.
	 * @param hour2
	 *            is the hour from 0 to 23. (0 is midnight, 23 is 11 pm.)
	 * @param min2
	 *            minutes from 0 to 59.
	 * @param sec2
	 *            seconds from 0 to 59.
	 * @param gmt
	 *            "GMT" for gmt time format else "undefined"
	 * @return true if the current time matches the given range.
	 ************************************************************************/

	public static boolean timeRange(Object hour1, Object min1, Object sec1,
			Object hour2, Object min2, Object sec2, Object gmt) {
		return SCRIPT_METHODS.timeRange(hour1, min1, sec1, hour2, min2, sec2,
				gmt);
	}

}
