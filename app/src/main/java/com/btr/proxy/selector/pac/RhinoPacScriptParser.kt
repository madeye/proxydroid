package com.btr.proxy.selector.pac

import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.*

class RhinoPacScriptParser(private val source: PacScriptSource) : ScriptableObject(), PacScriptParser {

    companion object {
        private const val serialVersionUID = 1L
        private const val TAG = "ProxyDroid.PAC"

        private val JS_FUNCTION_NAMES = arrayOf(
            "shExpMatch", "dnsResolve", "isResolvable", "isInNet", "dnsDomainIs",
            "isPlainHostName", "myIpAddress", "dnsDomainLevels", "localHostOrDomainIs",
            "weekdayRange", "dateRange", "timeRange"
        )

        private val SCRIPT_METHODS = PacScriptMethods()

        @JvmStatic
        fun isPlainHostName(host: String): Boolean = SCRIPT_METHODS.isPlainHostName(host)

        @JvmStatic
        fun dnsDomainIs(host: String, domain: String): Boolean = SCRIPT_METHODS.dnsDomainIs(host, domain)

        @JvmStatic
        fun localHostOrDomainIs(host: String, domain: String): Boolean = SCRIPT_METHODS.localHostOrDomainIs(host, domain)

        @JvmStatic
        fun isResolvable(host: String): Boolean = SCRIPT_METHODS.isResolvable(host)

        @JvmStatic
        fun isInNet(host: String, pattern: String, mask: String): Boolean = SCRIPT_METHODS.isInNet(host, pattern, mask)

        @JvmStatic
        fun dnsResolve(host: String): String = SCRIPT_METHODS.dnsResolve(host)

        @JvmStatic
        fun myIpAddress(): String = SCRIPT_METHODS.myIpAddress()

        @JvmStatic
        fun dnsDomainLevels(host: String): Int = SCRIPT_METHODS.dnsDomainLevels(host)

        @JvmStatic
        fun shExpMatch(str: String, shexp: String): Boolean = SCRIPT_METHODS.shExpMatch(str, shexp)

        @JvmStatic
        fun weekdayRange(wd1: String, wd2: String?, gmt: String?): Boolean = SCRIPT_METHODS.weekdayRange(wd1, wd2, gmt)

        @JvmStatic
        fun setCurrentTime(cal: Calendar?) {
            SCRIPT_METHODS.setCurrentTime(cal)
        }

        @JvmStatic
        fun dateRange(day1: Any?, month1: Any?, year1: Any?, day2: Any?, month2: Any?, year2: Any?, gmt: Any?): Boolean {
            return SCRIPT_METHODS.dateRange(day1, month1, year1, day2, month2, year2, gmt)
        }

        @JvmStatic
        fun timeRange(hour1: Any?, min1: Any?, sec1: Any?, hour2: Any?, min2: Any?, sec2: Any?, gmt: Any?): Boolean {
            return SCRIPT_METHODS.timeRange(hour1, min1, sec1, hour2, min2, sec2, gmt)
        }
    }

    private var scope: Scriptable? = null

    init {
        setupEngine()
    }

    @Throws(ProxyEvaluationException::class)
    fun setupEngine() {
        val context = ContextFactory().enterContext()
        try {
            defineFunctionProperties(JS_FUNCTION_NAMES, RhinoPacScriptParser::class.java, ScriptableObject.DONTENUM)
        } catch (e: Exception) {
            Log.e(TAG, "JS Engine setup error.", e)
            throw ProxyEvaluationException(e.message, e)
        }
        scope = context.initStandardObjects(this)
    }

    override fun getScriptSource(): PacScriptSource = source

    @Throws(ProxyEvaluationException::class)
    override fun evaluate(url: String, host: String): String {
        try {
            val script = StringBuilder(source.getScriptContent())
            val evalMethod = " ;FindProxyForURL (\"$url\",\"$host\")"
            script.append(evalMethod)

            val context = Context.enter()
            context.optimizationLevel = -1
            try {
                val result = context.evaluateString(scope, script.toString(), "userPacFile", 1, null)
                return Context.toString(result)
            } finally {
                Context.exit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "JS evaluation error.", e)
            throw ProxyEvaluationException("Error while executing PAC script: ${e.message}", e)
        }
    }

    override fun getClassName(): String = javaClass.simpleName
}
