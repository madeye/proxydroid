/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proxydroid.utils

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalProxyServer(
    private val localPort: Int,
    private val remoteHost: String,
    private val remotePort: Int,
    private val username: String?,
    private val password: String?
) : Runnable {

    private var serverSocket: ServerSocket? = null
    private var running = false
    private var executor: ExecutorService? = null

    companion object {
        private const val TAG = "LocalProxyServer"
        private const val BUFFER_SIZE = 8192
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        executor = Executors.newCachedThreadPool()
        Thread(this).start()
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        executor?.shutdownNow()
        executor = null
    }

    override fun run() {
        try {
            serverSocket = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "Local SOCKS5 proxy server started on port $localPort")

            while (running) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    executor?.execute(ClientHandler(clientSocket))
                } catch (e: IOException) {
                    if (running) {
                        Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error starting server", e)
        } finally {
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                // Ignore
            }
        }
    }

    private inner class ClientHandler(private val clientSocket: Socket) : Runnable {
        override fun run() {
            var remoteSocket: Socket? = null
            try {
                val clientIn = clientSocket.getInputStream()
                val clientOut = clientSocket.getOutputStream()

                // Connect to remote SOCKS5 proxy
                remoteSocket = Socket(remoteHost, remotePort)
                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                // Authenticate with remote proxy if needed
                if (!authenticate(remoteIn, remoteOut)) {
                    Log.e(TAG, "Authentication failed")
                    return
                }

                // Relay data between client and remote
                val clientToRemote = Thread { relay(clientIn, remoteOut) }
                val remoteToClient = Thread { relay(remoteIn, clientOut) }

                clientToRemote.start()
                remoteToClient.start()

                clientToRemote.join()
                remoteToClient.join()

            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            } finally {
                try {
                    clientSocket.close()
                } catch (e: IOException) {
                    // Ignore
                }
                try {
                    remoteSocket?.close()
                } catch (e: IOException) {
                    // Ignore
                }
            }
        }

        private fun authenticate(input: InputStream, output: OutputStream): Boolean {
            return try {
                // SOCKS5 greeting
                if (username != null && password != null) {
                    output.write(byteArrayOf(0x05, 0x01, 0x02)) // Version 5, 1 method, Username/Password
                } else {
                    output.write(byteArrayOf(0x05, 0x01, 0x00)) // Version 5, 1 method, No auth
                }
                output.flush()

                val response = ByteArray(2)
                if (input.read(response) != 2) return false
                if (response[0] != 0x05.toByte()) return false

                if (response[1] == 0x02.toByte() && username != null && password != null) {
                    // Username/Password authentication
                    val authRequest = ByteArray(3 + username.length + password.length)
                    authRequest[0] = 0x01
                    authRequest[1] = username.length.toByte()
                    System.arraycopy(username.toByteArray(), 0, authRequest, 2, username.length)
                    authRequest[2 + username.length] = password.length.toByte()
                    System.arraycopy(password.toByteArray(), 0, authRequest, 3 + username.length, password.length)
                    output.write(authRequest)
                    output.flush()

                    val authResponse = ByteArray(2)
                    if (input.read(authResponse) != 2) return false
                    if (authResponse[1] != 0x00.toByte()) return false
                }

                true
            } catch (e: IOException) {
                Log.e(TAG, "Authentication error", e)
                false
            }
        }

        private fun relay(input: InputStream, output: OutputStream) {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
            } catch (e: IOException) {
                // Connection closed
            }
        }
    }
}
