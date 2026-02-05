package com.btr.proxy.selector.pac

interface ScriptMethods {
    fun isPlainHostName(host: String): Boolean
    fun dnsDomainIs(host: String, domain: String): Boolean
    fun localHostOrDomainIs(host: String, domain: String): Boolean
    fun isResolvable(host: String): Boolean
    fun isResolvableEx(host: String): Boolean
    fun isInNet(host: String, pattern: String, mask: String): Boolean
    fun isInNetEx(ipAddress: String, ipPrefix: String): Boolean
    fun dnsResolve(host: String): String
    fun dnsResolveEx(host: String): String
    fun myIpAddress(): String
    fun myIpAddressEx(): String
    fun dnsDomainLevels(host: String): Int
    fun shExpMatch(str: String, shexp: String): Boolean
    fun weekdayRange(wd1: String, wd2: String?, gmt: String?): Boolean
    fun dateRange(day1: Any?, month1: Any?, year1: Any?, day2: Any?, month2: Any?, year2: Any?, gmt: Any?): Boolean
    fun timeRange(hour1: Any?, min1: Any?, sec1: Any?, hour2: Any?, min2: Any?, sec2: Any?, gmt: Any?): Boolean
    fun sortIpAddressList(ipAddressList: String): String
    fun getClientVersion(): String
}
