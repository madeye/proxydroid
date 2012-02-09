package com.btr.proxy.selector.pac;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TimeZone;

import android.util.Log;

/***************************************************************************
 * Implementation of PAC JavaScript functions.
 * 
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 *************************************************************************** 
 */
public class PacScriptMethods implements ScriptMethods {

	public static final String OVERRIDE_LOCAL_IP = "com.btr.proxy.pac.overrideLocalIP";

	private final static String GMT = "GMT";

	private final static List<String> DAYS = Collections
			.unmodifiableList(Arrays.asList("SUN", "MON", "TUE", "WED", "THU",
					"FRI", "SAT"));

	private final static List<String> MONTH = Collections
			.unmodifiableList(Arrays.asList("JAN", "FEB", "MAR", "APR", "MAY",
					"JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"));

	private Calendar currentTime;

	private final static String TAG = "ProxyDroid.PAC";

	/*************************************************************************
	 * Constructor
	 ************************************************************************/

	public PacScriptMethods() {
		super();
	}

	/*************************************************************************
	 * isPlainHostName
	 * 
	 * @see com.btr.proxy.selector.pac.ScriptMethods#isPlainHostName(java.lang.String)
	 ************************************************************************/

	@Override
	public boolean isPlainHostName(String host) {
		return host.indexOf(".") < 0;
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

	@Override
	public boolean dnsDomainIs(String host, String domain) {
		return host.endsWith(domain);
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

	@Override
	public boolean localHostOrDomainIs(String host, String domain) {
		return domain.startsWith(host);
	}

	/*************************************************************************
	 * Tries to resolve the host name. Returns true if succeeds.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @return true if resolvable else false.
	 ************************************************************************/

	@Override
	public boolean isResolvable(String host) {
		try {
			InetAddress.getByName(host).getHostAddress();
			return true;
		} catch (UnknownHostException ex) {
			Log.e(TAG, "Hostname not resolveable " + host);
			// Not resolvable
		}
		return false;
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

	@Override
	public boolean isInNet(String host, String pattern, String mask) {
		long lhost = parseIpAddressToLong(host);
		long lpattern = parseIpAddressToLong(pattern);
		long lmask = parseIpAddressToLong(mask);
		boolean result = (lhost & lmask) == lpattern;
		return result;
	}

	/*************************************************************************
	 * Convert a string representation of a IP to a long.
	 * 
	 * @param address
	 *            to convert.
	 * @return the address as long.
	 ************************************************************************/

	private long parseIpAddressToLong(String address) {
		long result = 0;
		String[] parts = address.split("\\.");
		long shift = 24;
		for (String part : parts) {
			long lpart = Long.parseLong(part);

			result |= (lpart << shift);
			shift -= 8;
		}
		return result;
	}

	/*************************************************************************
	 * Resolves the given DNS host name into an IP address, and returns it in
	 * the dot separated format as a string.
	 * 
	 * @param host
	 *            the host to resolve.
	 * @return the resolved IP, empty string if not resolvable.
	 ************************************************************************/

	@Override
	public String dnsResolve(String host) {
		try {
			return InetAddress.getByName(host).getHostAddress();
		} catch (UnknownHostException e) {
			Log.e(TAG, "DNS name not resolvable " + host);
			// Not resolvable.
		}
		return "";
	}

	/*************************************************************************
	 * Returns the IP address of the host that the process is running on, as a
	 * string in the dot-separated integer format.
	 * 
	 * @return an IP as string.
	 ************************************************************************/

	@Override
	public String myIpAddress() {
		try {
			String overrideIP = System.getProperty(OVERRIDE_LOCAL_IP);
			if (overrideIP != null && overrideIP.trim().length() > 0) {
				return overrideIP.trim();
			}
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			Log.e(TAG, "Local address not resolvable.");
			return "";
		}
	}

	/*************************************************************************
	 * Returns the number of DNS domain levels (number of dots) in the host
	 * name.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @return number of DNS domain levels.
	 ************************************************************************/

	@Override
	public int dnsDomainLevels(String host) {
		int count = 0;
		int startPos = 0;
		while ((startPos = host.indexOf(".", startPos + 1)) > -1) {
			count++;
		}
		return count;
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

	@Override
	public boolean shExpMatch(String str, String shexp) {
		StringTokenizer tokenizer = new StringTokenizer(shexp, "*");
		int startPos = 0;
		while (tokenizer.hasMoreTokens()) {
			String token = tokenizer.nextToken();
			// 07.05.2009 Incorrect? first token can be startsWith and last one
			// can be endsWith
			int temp = str.indexOf(token, startPos);
			if (temp == -1) {
				return false;
			} else {
				startPos = temp + token.length();
			}
		}
		return true;
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

	@Override
	public boolean weekdayRange(String wd1, String wd2, String gmt) {
		boolean useGmt = GMT.equalsIgnoreCase(wd2) || GMT.equalsIgnoreCase(gmt);
		Calendar cal = getCurrentTime(useGmt);

		int currentDay = cal.get(Calendar.DAY_OF_WEEK) - 1;
		int from = DAYS.indexOf(wd1 == null ? null : wd1.toUpperCase());
		int to = DAYS.indexOf(wd2 == null ? null : wd2.toUpperCase());
		if (to == -1) {
			to = from;
		}

		if (to < from) {
			return currentDay >= from || currentDay <= to;
		} else {
			return currentDay >= from && currentDay <= to;
		}
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

	public void setCurrentTime(Calendar cal) {
		this.currentTime = cal;
	}

	/*************************************************************************
	 * Gets a calendar set to the current time. This is used by the date and
	 * time based methods.
	 * 
	 * @param useGmt
	 *            flag to indicate if the calendar is to be created in GMT time
	 *            or local time.
	 * @return a Calendar set to the current time.
	 ************************************************************************/

	private Calendar getCurrentTime(boolean useGmt) {
		if (this.currentTime != null) { // Only used for unit tests
			return (Calendar) this.currentTime.clone();
		}
		return Calendar.getInstance(useGmt ? TimeZone.getTimeZone(GMT)
				: TimeZone.getDefault());
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

	@Override
	public boolean dateRange(Object day1, Object month1, Object year1,
			Object day2, Object month2, Object year2, Object gmt) {

		// Guess the parameter meanings.
		Map<String, Integer> params = new HashMap<String, Integer>();
		parseDateParam(params, day1);
		parseDateParam(params, month1);
		parseDateParam(params, year1);
		parseDateParam(params, day2);
		parseDateParam(params, month2);
		parseDateParam(params, year2);
		parseDateParam(params, gmt);

		// Get current date
		boolean useGmt = params.get("gmt") != null;
		Calendar cal = getCurrentTime(useGmt);
		Date current = cal.getTime();

		// Build the "from" date
		if (params.get("day1") != null) {
			cal.set(Calendar.DAY_OF_MONTH, params.get("day1"));
		}
		if (params.get("month1") != null) {
			cal.set(Calendar.MONTH, params.get("month1"));
		}
		if (params.get("year1") != null) {
			cal.set(Calendar.YEAR, params.get("year1"));
		}
		Date from = cal.getTime();

		// Build the "to" date
		Date to;
		if (params.get("day2") != null) {
			cal.set(Calendar.DAY_OF_MONTH, params.get("day2"));
		}
		if (params.get("month2") != null) {
			cal.set(Calendar.MONTH, params.get("month2"));
		}
		if (params.get("year2") != null) {
			cal.set(Calendar.YEAR, params.get("year2"));
		}
		to = cal.getTime();

		// Need to increment to the next month?
		if (to.before(from)) {
			cal.add(Calendar.MONTH, +1);
			to = cal.getTime();
		}
		// Need to increment to the next year?
		if (to.before(from)) {
			cal.add(Calendar.YEAR, +1);
			cal.add(Calendar.MONTH, -1);
			to = cal.getTime();
		}

		return current.compareTo(from) >= 0 && current.compareTo(to) <= 0;
	}

	/*************************************************************************
	 * Try to guess the type of the given parameter and put it into the params
	 * map.
	 * 
	 * @param params
	 *            a map to put the parsed parameters into.
	 * @param value
	 *            to parse and specify the type for.
	 ************************************************************************/

	private void parseDateParam(Map<String, Integer> params, Object value) {
		if (value instanceof Number) {
			int n = ((Number) value).intValue();
			if (n <= 31) {
				// Its a day
				if (params.get("day1") == null) {
					params.put("day1", n);
				} else {
					params.put("day2", n);
				}
			} else {
				// Its a year
				if (params.get("year1") == null) {
					params.put("year1", n);
				} else {
					params.put("year2", n);
				}
			}
		}

		if (value instanceof String) {
			int n = MONTH.indexOf(((String) value).toUpperCase());
			if (n > -1) {
				// Its a month
				if (params.get("month1") == null) {
					params.put("month1", n);
				} else {
					params.put("month2", n);
				}
			}
		}

		if (GMT.equalsIgnoreCase(String.valueOf(value))) {
			params.put("gmt", 1);
		}
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

	@Override
	public boolean timeRange(Object hour1, Object min1, Object sec1,
			Object hour2, Object min2, Object sec2, Object gmt) {
		boolean useGmt = GMT.equalsIgnoreCase(String.valueOf(min1))
				|| GMT.equalsIgnoreCase(String.valueOf(sec1))
				|| GMT.equalsIgnoreCase(String.valueOf(min2))
				|| GMT.equalsIgnoreCase(String.valueOf(gmt));

		Calendar cal = getCurrentTime(useGmt);
		cal.set(Calendar.MILLISECOND, 0);
		Date current = cal.getTime();
		Date from;
		Date to;
		if (sec2 instanceof Number) {
			cal.set(Calendar.HOUR_OF_DAY, ((Number) hour1).intValue());
			cal.set(Calendar.MINUTE, ((Number) min1).intValue());
			cal.set(Calendar.SECOND, ((Number) sec1).intValue());
			from = cal.getTime();

			cal.set(Calendar.HOUR_OF_DAY, ((Number) hour2).intValue());
			cal.set(Calendar.MINUTE, ((Number) min2).intValue());
			cal.set(Calendar.SECOND, ((Number) sec2).intValue());
			to = cal.getTime();
		} else if (hour2 instanceof Number) {
			cal.set(Calendar.HOUR_OF_DAY, ((Number) hour1).intValue());
			cal.set(Calendar.MINUTE, ((Number) min1).intValue());
			cal.set(Calendar.SECOND, 0);
			from = cal.getTime();

			cal.set(Calendar.HOUR_OF_DAY, ((Number) sec1).intValue());
			cal.set(Calendar.MINUTE, ((Number) hour2).intValue());
			cal.set(Calendar.SECOND, 59);
			to = cal.getTime();
		} else if (min1 instanceof Number) {
			cal.set(Calendar.HOUR_OF_DAY, ((Number) hour1).intValue());
			cal.set(Calendar.MINUTE, 0);
			cal.set(Calendar.SECOND, 0);
			from = cal.getTime();

			cal.set(Calendar.HOUR_OF_DAY, ((Number) min1).intValue());
			cal.set(Calendar.MINUTE, 59);
			cal.set(Calendar.SECOND, 59);
			to = cal.getTime();
		} else {
			cal.set(Calendar.HOUR_OF_DAY, ((Number) hour1).intValue());
			cal.set(Calendar.MINUTE, 0);
			cal.set(Calendar.SECOND, 0);
			from = cal.getTime();

			cal.set(Calendar.HOUR_OF_DAY, ((Number) hour1).intValue());
			cal.set(Calendar.MINUTE, 59);
			cal.set(Calendar.SECOND, 59);
			to = cal.getTime();
		}

		if (to.before(from)) {
			cal.setTime(to);
			cal.add(Calendar.DATE, +1);
			to = cal.getTime();
		}

		return current.compareTo(from) >= 0 && current.compareTo(to) <= 0;
	}

	// Microsoft PAC extensions for IPv6 support.

	/*************************************************************************
	 * isResolvableEx
	 * 
	 * @see com.btr.proxy.selector.pac.ScriptMethods#isResolvableEx(java.lang.String)
	 ************************************************************************/
	@Override
	public boolean isResolvableEx(String host) {
		return isResolvable(host);
	}

	/*************************************************************************
	 * isInNetEx
	 * 
	 * @see com.btr.proxy.selector.pac.ScriptMethods#isInNetEx(java.lang.String,
	 *      java.lang.String)
	 ************************************************************************/
	@Override
	public boolean isInNetEx(String ipAddress, String ipPrefix) {
		// TODO rossi 27.06.2011 Auto-generated method stub
		return false;
	}

	/*************************************************************************
	 * dnsResolveEx
	 * 
	 * @see com.btr.proxy.selector.pac.ScriptMethods#dnsResolveEx(java.lang.String)
	 ************************************************************************/
	@Override
	public String dnsResolveEx(String host) {
		StringBuilder result = new StringBuilder();
		try {
			InetAddress[] list = InetAddress.getAllByName(host);
			for (InetAddress inetAddress : list) {
				result.append(inetAddress.getHostAddress());
				result.append("; ");
			}
		} catch (UnknownHostException e) {
			Log.e(TAG, "DNS name not resolvable " + host);
		}
		return result.toString();
	}

	/*************************************************************************
	 * myIpAddressEx
	 * 
	 * @see com.btr.proxy.selector.pac.ScriptMethods#myIpAddressEx()
	 ************************************************************************/
	@Override
	public String myIpAddressEx() {
		String overrideIP = System.getProperty(OVERRIDE_LOCAL_IP);
		if (overrideIP != null && overrideIP.trim().length() > 0) {
			return overrideIP.trim();
		}
		return dnsResolveEx("localhost");
	}

	/*************************************************************************
	 * sortIpAddressList
	 * 
	 * @see com.btr.proxy.selector.pac.ScriptMethods#sortIpAddressList(java.lang.String)
	 ************************************************************************/
	@Override
	public String sortIpAddressList(String ipAddressList) {
		if (ipAddressList == null || ipAddressList.trim().length() == 0) {
			return "";
		}
		String[] ipAddressToken = ipAddressList.split(";");
		List<InetAddress> parsedAddresses = new ArrayList<InetAddress>();
		for (String ip : ipAddressToken) {
			try {
				parsedAddresses.add(InetAddress.getByName(ip));
			} catch (UnknownHostException e) {
				// TODO rossi 01.11.2011 Auto-generated catch block
				e.printStackTrace();
			}
		}
		Collections.sort(parsedAddresses, null);
		// TODO rossi 27.06.2011 Implement me.
		return ipAddressList;
	}

	/*************************************************************************
	 * getClientVersion
	 * 
	 * @see com.btr.proxy.selector.pac.ScriptMethods#getClientVersion()
	 ************************************************************************/
	@Override
	public String getClientVersion() {
		return "1.0";
	}

}
