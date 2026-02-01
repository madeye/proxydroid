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

package org.proxydroid.utils;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local SOCKS5 server that forwards connections through an HTTP proxy.
 * This allows tun2socks to work with HTTP proxies by providing a local SOCKS interface.
 */
public class LocalProxyServer {

    private static final String TAG = "LocalProxyServer";

    private static final int DEFAULT_PORT = 10800;
    private static final int SOCKS5_VERSION = 0x05;
    private static final int SOCKS5_CMD_CONNECT = 0x01;
    private static final int SOCKS5_ATYP_IPV4 = 0x01;
    private static final int SOCKS5_ATYP_DOMAIN = 0x03;
    private static final int SOCKS5_ATYP_IPV6 = 0x04;

    private Context context;
    private String remoteHost;
    private int remotePort;
    private String proxyType;
    private String username;
    private String password;

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;
    private Thread serverThread;
    private int localPort;

    public LocalProxyServer(Context context, String remoteHost, int remotePort,
            String proxyType, String username, String password) {
        this.context = context;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.proxyType = proxyType;
        this.username = username;
        this.password = password;
    }

    /**
     * Start the local SOCKS5 server
     */
    public boolean start() {
        if (running) {
            return true;
        }

        try {
            serverSocket = new ServerSocket(0); // Bind to any available port
            localPort = serverSocket.getLocalPort();
            executor = Executors.newCachedThreadPool();
            running = true;

            serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "Local SOCKS5 server started on port " + localPort);
                    while (running && !Thread.currentThread().isInterrupted()) {
                        try {
                            Socket clientSocket = serverSocket.accept();
                            executor.submit(new ClientHandler(clientSocket));
                        } catch (SocketException e) {
                            if (running) {
                                Log.e(TAG, "Socket accept error", e);
                            }
                        } catch (IOException e) {
                            if (running) {
                                Log.e(TAG, "IO error accepting connection", e);
                            }
                        }
                    }
                    Log.d(TAG, "Local SOCKS5 server stopped");
                }
            }, "local-socks-server");

            serverThread.start();
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to start local server", e);
            return false;
        }
    }

    /**
     * Stop the local server
     */
    public void stop() {
        running = false;

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
            serverSocket = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }

        if (serverThread != null) {
            serverThread.interrupt();
            try {
                serverThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverThread = null;
        }
    }

    /**
     * Get the local port the server is listening on
     */
    public int getPort() {
        return localPort;
    }

    /**
     * Check if the server is running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Handler for individual client connections
     */
    private class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try {
                handleSocks5Connection();
            } catch (IOException e) {
                Log.d(TAG, "Connection closed: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        private void handleSocks5Connection() throws IOException {
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // SOCKS5 handshake
            int version = in.read();
            if (version != SOCKS5_VERSION) {
                Log.w(TAG, "Unsupported SOCKS version: " + version);
                return;
            }

            // Read authentication methods
            int numMethods = in.read();
            byte[] methods = new byte[numMethods];
            in.read(methods);

            // Reply with no authentication required
            out.write(new byte[]{SOCKS5_VERSION, 0x00});
            out.flush();

            // Read connect request
            version = in.read();
            if (version != SOCKS5_VERSION) {
                return;
            }

            int cmd = in.read();
            if (cmd != SOCKS5_CMD_CONNECT) {
                // Send command not supported error
                out.write(new byte[]{SOCKS5_VERSION, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                return;
            }

            in.read(); // Reserved

            // Read address type and destination
            int addrType = in.read();
            String destHost;
            int destPort;

            switch (addrType) {
                case SOCKS5_ATYP_IPV4:
                    byte[] ipv4 = new byte[4];
                    in.read(ipv4);
                    destHost = String.format("%d.%d.%d.%d",
                            ipv4[0] & 0xFF, ipv4[1] & 0xFF, ipv4[2] & 0xFF, ipv4[3] & 0xFF);
                    break;
                case SOCKS5_ATYP_DOMAIN:
                    int domainLen = in.read();
                    byte[] domain = new byte[domainLen];
                    in.read(domain);
                    destHost = new String(domain, StandardCharsets.UTF_8);
                    break;
                case SOCKS5_ATYP_IPV6:
                    byte[] ipv6 = new byte[16];
                    in.read(ipv6);
                    // Convert to string representation
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", ipv6[i] & 0xFF, ipv6[i + 1] & 0xFF));
                    }
                    destHost = sb.toString();
                    break;
                default:
                    // Send address type not supported error
                    out.write(new byte[]{SOCKS5_VERSION, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                    out.flush();
                    return;
            }

            // Read destination port
            destPort = (in.read() << 8) | in.read();

            Log.d(TAG, "Connect request to " + destHost + ":" + destPort);

            // Connect through HTTP proxy
            Socket proxySocket = null;
            try {
                proxySocket = connectThroughHttpProxy(destHost, destPort);

                // Send success response
                out.write(new byte[]{
                        SOCKS5_VERSION, 0x00, 0x00, 0x01,
                        0, 0, 0, 0,  // Bound address (0.0.0.0)
                        0, 0         // Bound port (0)
                });
                out.flush();

                // Relay data between client and proxy
                relayData(clientSocket, proxySocket);

            } catch (IOException e) {
                Log.e(TAG, "Failed to connect through proxy: " + e.getMessage());
                // Send connection refused error
                out.write(new byte[]{SOCKS5_VERSION, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
            } finally {
                if (proxySocket != null) {
                    try {
                        proxySocket.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }

        private Socket connectThroughHttpProxy(String destHost, int destPort) throws IOException {
            Socket proxySocket = new Socket();
            proxySocket.connect(new InetSocketAddress(remoteHost, remotePort), 10000);

            InputStream proxyIn = proxySocket.getInputStream();
            OutputStream proxyOut = proxySocket.getOutputStream();

            // Send HTTP CONNECT request
            StringBuilder request = new StringBuilder();
            request.append("CONNECT ").append(destHost).append(":").append(destPort)
                    .append(" HTTP/1.1\r\n");
            request.append("Host: ").append(destHost).append(":").append(destPort).append("\r\n");

            // Add proxy authentication if needed
            if (username != null && !username.isEmpty()) {
                String auth = username + ":" + (password != null ? password : "");
                String encodedAuth = Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP);
                request.append("Proxy-Authorization: Basic ").append(encodedAuth).append("\r\n");
            }

            request.append("Proxy-Connection: keep-alive\r\n");
            request.append("\r\n");

            proxyOut.write(request.toString().getBytes(StandardCharsets.UTF_8));
            proxyOut.flush();

            // Read response
            StringBuilder response = new StringBuilder();
            int ch;
            while ((ch = proxyIn.read()) != -1) {
                response.append((char) ch);
                if (response.toString().endsWith("\r\n\r\n")) {
                    break;
                }
            }

            String responseStr = response.toString();
            Log.d(TAG, "Proxy response: " + responseStr.split("\r\n")[0]);

            // Check for success (HTTP/1.x 200)
            if (!responseStr.contains(" 200 ")) {
                proxySocket.close();
                throw new IOException("Proxy connection failed: " + responseStr.split("\r\n")[0]);
            }

            return proxySocket;
        }

        private void relayData(final Socket client, final Socket proxy) {
            Thread clientToProxy = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        copyStream(client.getInputStream(), proxy.getOutputStream());
                    } catch (IOException e) {
                        // Connection closed
                    }
                }
            });

            Thread proxyToClient = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        copyStream(proxy.getInputStream(), client.getOutputStream());
                    } catch (IOException e) {
                        // Connection closed
                    }
                }
            });

            clientToProxy.start();
            proxyToClient.start();

            try {
                clientToProxy.join();
                proxyToClient.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void copyStream(InputStream in, OutputStream out) throws IOException {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        }
    }
}
