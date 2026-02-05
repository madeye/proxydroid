package com.btr.proxy.selector.pac

import android.util.Log
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.*

class PacScriptMethods : ScriptMethods {

    companion object {
        const val OVERRIDE_LOCAL_IP = "com.btr.proxy.pac.overrideLocalIP"
        private const val GMT = "GMT"
        private const val TAG = "ProxyDroid.PAC"

        private val DAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
        private val MONTH = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    }

    private var currentTime: Calendar? = null

    override fun isPlainHostName(host: String): Boolean = host.indexOf(".") < 0

    override fun dnsDomainIs(host: String, domain: String): Boolean = host.endsWith(domain)

    override fun localHostOrDomainIs(host: String, domain: String): Boolean = domain.startsWith(host)

    override fun isResolvable(host: String): Boolean {
        return try {
            InetAddress.getByName(host).hostAddress
            true
        } catch (ex: UnknownHostException) {
            Log.e(TAG, "Hostname not resolvable $host")
            false
        }
    }

    override fun isInNet(host: String, pattern: String, mask: String): Boolean {
        val lhost = parseIpAddressToLong(host)
        val lpattern = parseIpAddressToLong(pattern)
        val lmask = parseIpAddressToLong(mask)
        return (lhost and lmask) == lpattern
    }

    private fun parseIpAddressToLong(address: String): Long {
        var result = 0L
        val parts = address.split(".")
        var shift = 24L
        for (part in parts) {
            val lpart = part.toLong()
            result = result or (lpart shl shift.toInt())
            shift -= 8
        }
        return result
    }

    override fun dnsResolve(host: String): String {
        return try {
            InetAddress.getByName(host).hostAddress ?: ""
        } catch (e: UnknownHostException) {
            Log.e(TAG, "DNS name not resolvable $host")
            ""
        }
    }

    override fun myIpAddress(): String {
        return try {
            val overrideIP = System.getProperty(OVERRIDE_LOCAL_IP)
            if (!overrideIP.isNullOrBlank()) {
                overrideIP.trim()
            } else {
                InetAddress.getLocalHost().hostAddress ?: ""
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Local address not resolvable.")
            ""
        }
    }

    override fun dnsDomainLevels(host: String): Int {
        var count = 0
        var startPos = 0
        while (host.indexOf(".", startPos + 1).also { startPos = it } > -1) {
            count++
        }
        return count
    }

    override fun shExpMatch(str: String, shexp: String): Boolean {
        val tokenizer = StringTokenizer(shexp, "*")
        var startPos = 0
        while (tokenizer.hasMoreTokens()) {
            val token = tokenizer.nextToken()
            val temp = str.indexOf(token, startPos)
            if (temp == -1) {
                return false
            } else {
                startPos = temp + token.length
            }
        }
        return true
    }

    override fun weekdayRange(wd1: String, wd2: String?, gmt: String?): Boolean {
        val useGmt = GMT.equals(wd2, ignoreCase = true) || GMT.equals(gmt, ignoreCase = true)
        val cal = getCurrentTime(useGmt)

        val currentDay = cal.get(Calendar.DAY_OF_WEEK) - 1
        val from = DAYS.indexOf(wd1.uppercase())
        var to = DAYS.indexOf(wd2?.uppercase() ?: "")
        if (to == -1) to = from

        return if (to < from) {
            currentDay >= from || currentDay <= to
        } else {
            currentDay in from..to
        }
    }

    fun setCurrentTime(cal: Calendar?) {
        this.currentTime = cal
    }

    private fun getCurrentTime(useGmt: Boolean): Calendar {
        currentTime?.let { return it.clone() as Calendar }
        return Calendar.getInstance(if (useGmt) TimeZone.getTimeZone(GMT) else TimeZone.getDefault())
    }

    override fun dateRange(day1: Any?, month1: Any?, year1: Any?, day2: Any?, month2: Any?, year2: Any?, gmt: Any?): Boolean {
        val params = mutableMapOf<String, Int>()
        parseDateParam(params, day1)
        parseDateParam(params, month1)
        parseDateParam(params, year1)
        parseDateParam(params, day2)
        parseDateParam(params, month2)
        parseDateParam(params, year2)
        parseDateParam(params, gmt)

        val useGmt = params["gmt"] != null
        val cal = getCurrentTime(useGmt)
        val current = cal.time

        params["day1"]?.let { cal.set(Calendar.DAY_OF_MONTH, it) }
        params["month1"]?.let { cal.set(Calendar.MONTH, it) }
        params["year1"]?.let { cal.set(Calendar.YEAR, it) }
        val from = cal.time

        params["day2"]?.let { cal.set(Calendar.DAY_OF_MONTH, it) }
        params["month2"]?.let { cal.set(Calendar.MONTH, it) }
        params["year2"]?.let { cal.set(Calendar.YEAR, it) }
        var to = cal.time

        if (to.before(from)) {
            cal.add(Calendar.MONTH, 1)
            to = cal.time
        }
        if (to.before(from)) {
            cal.add(Calendar.YEAR, 1)
            cal.add(Calendar.MONTH, -1)
            to = cal.time
        }

        return current >= from && current <= to
    }

    private fun parseDateParam(params: MutableMap<String, Int>, value: Any?) {
        when (value) {
            is Number -> {
                val n = value.toInt()
                if (n <= 31) {
                    if (params["day1"] == null) params["day1"] = n else params["day2"] = n
                } else {
                    if (params["year1"] == null) params["year1"] = n else params["year2"] = n
                }
            }
            is String -> {
                val n = MONTH.indexOf(value.uppercase())
                if (n > -1) {
                    if (params["month1"] == null) params["month1"] = n else params["month2"] = n
                }
            }
        }
        if (GMT.equals(value.toString(), ignoreCase = true)) {
            params["gmt"] = 1
        }
    }

    override fun timeRange(hour1: Any?, min1: Any?, sec1: Any?, hour2: Any?, min2: Any?, sec2: Any?, gmt: Any?): Boolean {
        val useGmt = GMT.equals(min1.toString(), ignoreCase = true) ||
                GMT.equals(sec1.toString(), ignoreCase = true) ||
                GMT.equals(min2.toString(), ignoreCase = true) ||
                GMT.equals(gmt.toString(), ignoreCase = true)

        val cal = getCurrentTime(useGmt)
        cal.set(Calendar.MILLISECOND, 0)
        val current = cal.time

        val from: Date
        val to: Date

        when {
            sec2 is Number -> {
                cal.set(Calendar.HOUR_OF_DAY, (hour1 as Number).toInt())
                cal.set(Calendar.MINUTE, (min1 as Number).toInt())
                cal.set(Calendar.SECOND, (sec1 as Number).toInt())
                from = cal.time

                cal.set(Calendar.HOUR_OF_DAY, (hour2 as Number).toInt())
                cal.set(Calendar.MINUTE, (min2 as Number).toInt())
                cal.set(Calendar.SECOND, sec2.toInt())
                to = cal.time
            }
            hour2 is Number -> {
                cal.set(Calendar.HOUR_OF_DAY, (hour1 as Number).toInt())
                cal.set(Calendar.MINUTE, (min1 as Number).toInt())
                cal.set(Calendar.SECOND, 0)
                from = cal.time

                cal.set(Calendar.HOUR_OF_DAY, (sec1 as Number).toInt())
                cal.set(Calendar.MINUTE, hour2.toInt())
                cal.set(Calendar.SECOND, 59)
                to = cal.time
            }
            min1 is Number -> {
                cal.set(Calendar.HOUR_OF_DAY, (hour1 as Number).toInt())
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                from = cal.time

                cal.set(Calendar.HOUR_OF_DAY, min1.toInt())
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                to = cal.time
            }
            else -> {
                cal.set(Calendar.HOUR_OF_DAY, (hour1 as Number).toInt())
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                from = cal.time

                cal.set(Calendar.HOUR_OF_DAY, (hour1 as Number).toInt())
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                to = cal.time
            }
        }

        var adjustedTo = to
        if (adjustedTo.before(from)) {
            cal.time = adjustedTo
            cal.add(Calendar.DATE, 1)
            adjustedTo = cal.time
        }

        return current >= from && current <= adjustedTo
    }

    override fun isResolvableEx(host: String): Boolean = isResolvable(host)

    override fun isInNetEx(ipAddress: String, ipPrefix: String): Boolean = false

    override fun dnsResolveEx(host: String): String {
        val result = StringBuilder()
        try {
            val list = InetAddress.getAllByName(host)
            for (inetAddress in list) {
                result.append(inetAddress.hostAddress)
                result.append("; ")
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "DNS name not resolvable $host")
        }
        return result.toString()
    }

    override fun myIpAddressEx(): String {
        val overrideIP = System.getProperty(OVERRIDE_LOCAL_IP)
        return if (!overrideIP.isNullOrBlank()) overrideIP.trim() else dnsResolveEx("localhost")
    }

    override fun sortIpAddressList(ipAddressList: String): String {
        if (ipAddressList.isBlank()) return ""
        return ipAddressList
    }

    override fun getClientVersion(): String = "1.0"
}
