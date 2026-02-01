package org.proxydroid

import android.util.Log
import java.io.IOException
import java.net.Socket

class InnerSocketBuilder(
    private val proxyHost: String = "127.0.0.1",
    private val proxyPort: Int = 1053,
    target: String
) {
    var socket: Socket? = null
        private set

    var isConnected: Boolean = false
        private set

    companion object {
        private const val TAG = "CMWRAP->InnerSocketBuilder"
    }

    init {
        connect()
    }

    private fun connect() {
        Log.v(TAG, "建立通道")

        try {
            socket = Socket(proxyHost, proxyPort).apply {
                keepAlive = true
                soTimeout = 60 * 1000
            }
            isConnected = true
        } catch (e: IOException) {
            Log.e(TAG, "建立隧道失败：${e.localizedMessage}")
        }
    }
}
