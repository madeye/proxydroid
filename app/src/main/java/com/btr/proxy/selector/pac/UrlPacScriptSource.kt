package com.btr.proxy.selector.pac

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class UrlPacScriptSource(private val scriptUrl: String) : PacScriptSource {

    companion object {
        private const val TAG = "ProxyDroid.PAC"
    }

    private var scriptContent: String? = null
    private var expireAtMillis: Long = 0

    @Synchronized
    @Throws(IOException::class)
    override fun getScriptContent(): String {
        if (scriptContent == null || (expireAtMillis > 0 && expireAtMillis > System.currentTimeMillis())) {
            try {
                scriptContent = if (scriptUrl.startsWith("file:/") || !scriptUrl.contains(":/")) {
                    readPacFileContent(scriptUrl)
                } else {
                    downloadPacContent(scriptUrl)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Loading script failed.", e)
                scriptContent = ""
                throw e
            }
        }
        return scriptContent ?: ""
    }

    @Throws(IOException::class)
    private fun readPacFileContent(scriptUrl: String): String {
        try {
            val file = if (!scriptUrl.contains(":/")) {
                File(scriptUrl)
            } else {
                File(URL(scriptUrl).toURI())
            }

            BufferedReader(FileReader(file)).use { r ->
                val result = StringBuilder()
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    result.append(line).append("\n")
                }
                return result.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "File reading error.", e)
            throw IOException(e.message)
        }
    }

    @Throws(IOException::class)
    private fun downloadPacContent(url: String?): String {
        if (url == null) {
            throw IOException("Invalid PAC script URL: null")
        }

        val con = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        con.connectTimeout = 15 * 1000
        con.readTimeout = 20 * 1000
        con.instanceFollowRedirects = true
        con.setRequestProperty("accept", "application/x-ns-proxy-autoconfig, */*;q=0.8")

        if (con.responseCode != 200) {
            throw IOException("Server returned: ${con.responseCode} ${con.responseMessage}")
        }

        expireAtMillis = con.expiration

        val charsetName = parseCharsetFromHeader(con.contentType)
        BufferedReader(InputStreamReader(con.inputStream, charsetName)).use { r ->
            val result = StringBuilder()
            try {
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    result.append(line).append("\n")
                }
            } finally {
                con.disconnect()
            }
            return result.toString()
        }
    }

    internal fun parseCharsetFromHeader(contentType: String?): String {
        var result = "ISO-8859-1"
        if (contentType != null) {
            val paramList = contentType.split(";")
            for (param in paramList) {
                if (param.lowercase().trim().startsWith("charset") && param.contains("=")) {
                    result = param.substring(param.indexOf("=") + 1).trim()
                }
            }
        }
        return result
    }

    override fun toString(): String = scriptUrl
}
