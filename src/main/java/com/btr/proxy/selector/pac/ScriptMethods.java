package com.btr.proxy.selector.pac;

/***************************************************************************
 * Defines the public interface for PAC scripts.
 * 
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 ***************************************************************************/
public interface ScriptMethods {

	public boolean isPlainHostName(String host);

	/*************************************************************************
	 * Tests if an URL is in a given domain.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @param domain
	 *            is the domain name to test the host name against.
	 * @return true if the domain of host name matches.
	 ************************************************************************/

	public boolean dnsDomainIs(String host, String domain);

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

	public boolean localHostOrDomainIs(String host, String domain);

	/*************************************************************************
	 * Tries to resolve the host name. Returns true if succeeds.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @return true if resolvable else false.
	 ************************************************************************/

	public boolean isResolvable(String host);

	/*************************************************************************
	 * Tries to resolve the host name. Returns true if succeeds to resolve the
	 * host to an IPv4 or IPv6 address.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @return true if resolvable else false.
	 ************************************************************************/

	public boolean isResolvableEx(String host);

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

	public boolean isInNet(String host, String pattern, String mask);

	/*************************************************************************
	 * Extension of the isInNet method to support IPv6.
	 * 
	 * @param ipAddress
	 *            an IP4 or IP6 address
	 * @param ipPrefix
	 *            A string containing colon delimited IP prefix with top n bits
	 *            specified in the bit field (i.e. 3ffe:8311:ffff::/48 or
	 *            123.112.0.0/16).
	 * @return true if the host is in the given subnet, else false.
	 ************************************************************************/

	public boolean isInNetEx(String ipAddress, String ipPrefix);

	/*************************************************************************
	 * Resolves the given DNS host name into an IP address, and returns it in
	 * the dot separated format as a string.
	 * 
	 * @param host
	 *            the host to resolve.
	 * @return the resolved IP, empty string if not resolvable.
	 ************************************************************************/

	public String dnsResolve(String host);

	/*************************************************************************
	 * @param host
	 *            the host to resolve
	 * @return a semicolon separated list of IP6 and IP4 addresses the host name
	 *         resolves to, empty string if not resolvable.
	 ************************************************************************/

	public String dnsResolveEx(String host);

	/*************************************************************************
	 * Returns the IP address of the host that the process is running on, as a
	 * string in the dot-separated integer format.
	 * 
	 * @return an IP as string.
	 ************************************************************************/

	public String myIpAddress();

	/*************************************************************************
	 * Returns a list of IP4 and IP6 addresses of the host that the process is
	 * running on. The list is separated with semicolons.
	 * 
	 * @return the list, empty string if not available.
	 ************************************************************************/

	public String myIpAddressEx();

	/*************************************************************************
	 * Returns the number of DNS domain levels (number of dots) in the host
	 * name.
	 * 
	 * @param host
	 *            is the host name from the URL.
	 * @return number of DNS domain levels.
	 ************************************************************************/

	public int dnsDomainLevels(String host);

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

	public boolean shExpMatch(String str, String shexp);

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

	public boolean weekdayRange(String wd1, String wd2, String gmt);

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

	public boolean dateRange(Object day1, Object month1, Object year1,
			Object day2, Object month2, Object year2, Object gmt);

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

	public boolean timeRange(Object hour1, Object min1, Object sec1,
			Object hour2, Object min2, Object sec2, Object gmt);

	/*************************************************************************
	 * Sorts a list of IP4 and IP6 addresses. Separated by semicolon. Dual
	 * addresses first, then IPv6 and last IPv4.
	 * 
	 * @param ipAddressList
	 *            the address list.
	 * @return the sorted list, empty string if sort is not possible
	 ************************************************************************/

	public String sortIpAddressList(String ipAddressList);

	/*************************************************************************
	 * Gets the version of the PAC extension that is available.
	 * 
	 * @return the extension version, currently 1.0
	 ************************************************************************/

	public String getClientVersion();

}
