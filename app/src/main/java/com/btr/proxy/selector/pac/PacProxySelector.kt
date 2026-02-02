package com.btr.proxy.selector.pac

import android.util.Log
import java.net.URI

class PacProxySelector(pacSource: PacScriptSource) {

    companion object {
        private const val PAC_SOCKS = "SOCKS"
        private const val PAC_DIRECT = "DIRECT"
        private const val PAC_HTTPS = "HTTPS"
        private const val TAG = "ProxyDroid.PAC"
    }

    private var pacScriptParser: PacScriptParser? = null

    init {
        selectEngine(pacSource)
    }

    private fun selectEngine(pacSource: PacScriptSource) {
        try {
            pacScriptParser = RhinoPacScriptParser(pacSource)
        } catch (e: Exception) {
            Log.e(TAG, "PAC parser error.", e)
        }
    }

    fun select(uri: URI): List<Proxy>? {
        if (uri.host == null) {
            throw IllegalArgumentException("URI must not be null.")
        }

        val scriptSource = pacScriptParser?.getScriptSource()
        if (scriptSource.toString().contains(uri.host)) {
            return null
        }

        return findProxy(uri)
    }

    private fun findProxy(uri: URI): List<Proxy>? {
        return try {
            val proxies = mutableListOf<Proxy>()
            val parseResult = pacScriptParser?.evaluate(uri.toString(), uri.host) ?: return null
            val proxyDefinitions = parseResult.split(";")

            for (proxyDef in proxyDefinitions) {
                if (proxyDef.trim().isNotEmpty()) {
                    proxies.add(buildProxyFromPacResult(proxyDef))
                }
            }
            proxies
        } catch (e: ProxyEvaluationException) {
            Log.e(TAG, "PAC resolving error.", e)
            null
        }
    }

    private fun buildProxyFromPacResult(pacResult: String?): Proxy {
        if (pacResult == null || pacResult.trim().length < 6) {
            return Proxy.NO_PROXY
        }

        val proxyDef = pacResult.trim()
        if (proxyDef.uppercase().startsWith(PAC_DIRECT)) {
            return Proxy.NO_PROXY
        }

        var type = Proxy.TYPE_HTTP
        if (proxyDef.uppercase().startsWith(PAC_SOCKS)) {
            type = Proxy.TYPE_SOCKS5
        }
        if (proxyDef.uppercase().startsWith(PAC_HTTPS)) {
            type = Proxy.TYPE_HTTPS
        }

        var host = proxyDef.substring(6)
        var port = if (type == Proxy.TYPE_HTTPS) 443 else 80

        val indexOfPort = host.indexOf(':')
        if (indexOfPort != -1) {
            port = host.substring(indexOfPort + 1).trim().toInt()
            host = host.substring(0, indexOfPort).trim()
        }

        return Proxy(host, port, type)
    }
}
