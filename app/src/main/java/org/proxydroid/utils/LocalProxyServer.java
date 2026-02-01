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

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local SOCKS5 proxy server that converts HTTP proxy connections to SOCKS5.
 * This is used when the upstream proxy is HTTP/HTTPS but tun2socks requires SOCKS5.
 */
public class LocalProxyServer {

    private static final String TAG = "LocalProxyServer";
    private static final int SOCKS5_VERSION = 0x05;
    private static final int SOCKS5_AUTH_NONE = 0x00;
    private static final int SOCKS5_AUTH_PASSWORD = 0x02;
    private static final int SOCKS5_CMD_CONNECT = 0x01;
    private static final int SOCKS5_ATYP_IPV4 = 0x01;
    private static final int SOCKS5_ATYP_DOMAIN = 0x03;
    private static final int SOCKS5_ATYP_IPV6 = 0x04;

    private final int localPort;
    private final String upstreamHost;
    private final int upstreamPort;
    private final String upstreamType;
    private final String upstreamUser;
    private final String upstreamPassword;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread serverThread;

    public LocalProxyServer(int localPort, String upstreamHost, int upstreamPort,
                            String upstreamType, String upstreamUser, String upstreamPassword) {
        this.localPort = localPort;
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.upstreamType = upstreamType;
        this.upstreamUser = upstreamUser != null ? upstreamUser : "";
        this.upstreamPassword = upstreamPassword != null ? upstreamPassword : "";
    }

    public void start() throws IOException {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Local proxy server is already running");
            return;
        }

        serverSocket = new ServerSocket(localPort, 50, java.net.InetAddress.getByName("127.0.0.1"));
        executorService = Executors.newCachedThreadPool();

        serverThread = new Thread(() -> {
            Log.i(TAG, "Local SOCKS5 server started on port " + localPort);
            while (isRunning.get() && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executorService.submit(() -> handleClient(clientSocket));
                } catch (SocketException e) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Socket error", e);
                    }
                } catch (IOException e) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Accept error", e);
                    }
                }
            }
        }, "LocalProxyServer");

        serverThread.start();
    }

    public void stop() {
        if (!isRunning.getAndSet(false)) {
            return;
        }

        Log.i(TAG, "Stopping local proxy server");

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }

        if (executorService != null) {
            executorService.shutdownNow();
        }

        if (serverThread != null) {
            try {
                serverThread.join(2000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for server thread");
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try {
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            // SOCKS5 handshake - read auth methods
            int version = clientIn.read();
            if (version != SOCKS5_VERSION) {
                Log.e(TAG, "Invalid SOCKS version: " + version);
                clientSocket.close();
                return;
            }

            int nmethods = clientIn.read();
            byte[] methods = new byte[nmethods];
            readFully(clientIn, methods);

            // Reply with no auth required (we handle auth to upstream ourselves)
            clientOut.write(new byte[]{SOCKS5_VERSION, SOCKS5_AUTH_NONE});
            clientOut.flush();

            // Read connection request
            version = clientIn.read();
            int cmd = clientIn.read();
            int rsv = clientIn.read();
            int atyp = clientIn.read();

            if (version != SOCKS5_VERSION || cmd != SOCKS5_CMD_CONNECT) {
                sendSocks5Error(clientOut, (byte) 0x07); // Command not supported
                clientSocket.close();
                return;
            }

            String targetHost;
            int targetPort;

            switch (atyp) {
                case SOCKS5_ATYP_IPV4:
                    byte[] ipv4 = new byte[4];
                    readFully(clientIn, ipv4);
                    targetHost = String.format("%d.%d.%d.%d",
                            ipv4[0] & 0xFF, ipv4[1] & 0xFF, ipv4[2] & 0xFF, ipv4[3] & 0xFF);
                    break;
                case SOCKS5_ATYP_DOMAIN:
                    int domainLen = clientIn.read();
                    byte[] domain = new byte[domainLen];
                    readFully(clientIn, domain);
                    targetHost = new String(domain, StandardCharsets.UTF_8);
                    break;
                case SOCKS5_ATYP_IPV6:
                    byte[] ipv6 = new byte[16];
                    readFully(clientIn, ipv6);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(":");
                        sb.append(String.format("%02x%02x", ipv6[i] & 0xFF, ipv6[i + 1] & 0xFF));
                    }
                    targetHost = sb.toString();
                    break;
                default:
                    sendSocks5Error(clientOut, (byte) 0x08); // Address type not supported
                    clientSocket.close();
                    return;
            }

            byte[] portBytes = new byte[2];
            readFully(clientIn, portBytes);
            targetPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

            // Connect to upstream proxy and relay
            Socket upstreamSocket = connectToUpstream(targetHost, targetPort);
            if (upstreamSocket == null) {
                sendSocks5Error(clientOut, (byte) 0x05); // Connection refused
                clientSocket.close();
                return;
            }

            // Send success response
            byte[] response = new byte[]{
                    SOCKS5_VERSION, 0x00, 0x00, SOCKS5_ATYP_IPV4,
                    0x00, 0x00, 0x00, 0x00,  // Bound address (0.0.0.0)
                    0x00, 0x00               // Bound port (0)
            };
            clientOut.write(response);
            clientOut.flush();

            // Start bidirectional relay
            relay(clientSocket, upstreamSocket);

        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Socket connectToUpstream(String targetHost, int targetPort) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(upstreamHost, upstreamPort), 10000);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            if ("socks5".equals(upstreamType) || "socks4".equals(upstreamType)) {
                return connectSocks5(socket, in, out, targetHost, targetPort);
            } else {
                // HTTP CONNECT for http, https, http-tunnel
                return connectHttpTunnel(socket, in, out, targetHost, targetPort);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to upstream: " + targetHost + ":" + targetPort, e);
            return null;
        }
    }

    private Socket connectSocks5(Socket socket, InputStream in, OutputStream out,
                                  String targetHost, int targetPort) throws IOException {
        // SOCKS5 handshake
        boolean hasAuth = !upstreamUser.isEmpty();
        if (hasAuth) {
            out.write(new byte[]{SOCKS5_VERSION, 0x02, SOCKS5_AUTH_NONE, SOCKS5_AUTH_PASSWORD});
        } else {
            out.write(new byte[]{SOCKS5_VERSION, 0x01, SOCKS5_AUTH_NONE});
        }
        out.flush();

        int version = in.read();
        int method = in.read();

        if (version != SOCKS5_VERSION) {
            Log.e(TAG, "Upstream SOCKS version mismatch: " + version);
            socket.close();
            return null;
        }

        if (method == SOCKS5_AUTH_PASSWORD && hasAuth) {
            // Send username/password auth
            byte[] userBytes = upstreamUser.getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = upstreamPassword.getBytes(StandardCharsets.UTF_8);
            byte[] authRequest = new byte[3 + userBytes.length + passBytes.length];
            authRequest[0] = 0x01; // Auth version
            authRequest[1] = (byte) userBytes.length;
            System.arraycopy(userBytes, 0, authRequest, 2, userBytes.length);
            authRequest[2 + userBytes.length] = (byte) passBytes.length;
            System.arraycopy(passBytes, 0, authRequest, 3 + userBytes.length, passBytes.length);
            out.write(authRequest);
            out.flush();

            int authVersion = in.read();
            int authStatus = in.read();
            if (authStatus != 0x00) {
                Log.e(TAG, "Upstream SOCKS auth failed");
                socket.close();
                return null;
            }
        } else if (method != SOCKS5_AUTH_NONE) {
            Log.e(TAG, "Unsupported auth method: " + method);
            socket.close();
            return null;
        }

        // Send connect request
        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        byte[] request = new byte[7 + hostBytes.length];
        request[0] = SOCKS5_VERSION;
        request[1] = SOCKS5_CMD_CONNECT;
        request[2] = 0x00; // Reserved
        request[3] = SOCKS5_ATYP_DOMAIN;
        request[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, request, 5, hostBytes.length);
        request[5 + hostBytes.length] = (byte) ((targetPort >> 8) & 0xFF);
        request[6 + hostBytes.length] = (byte) (targetPort & 0xFF);
        out.write(request);
        out.flush();

        // Read response
        byte[] response = new byte[4];
        readFully(in, response);

        if (response[1] != 0x00) {
            Log.e(TAG, "Upstream SOCKS connect failed: " + response[1]);
            socket.close();
            return null;
        }

        // Skip bound address
        int atyp = response[3];
        switch (atyp) {
            case SOCKS5_ATYP_IPV4:
                in.skip(4 + 2);
                break;
            case SOCKS5_ATYP_DOMAIN:
                int len = in.read();
                in.skip(len + 2);
                break;
            case SOCKS5_ATYP_IPV6:
                in.skip(16 + 2);
                break;
        }

        return socket;
    }

    private Socket connectHttpTunnel(Socket socket, InputStream in, OutputStream out,
                                      String targetHost, int targetPort) throws IOException {
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(targetHost).append(":").append(targetPort).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(targetHost).append(":").append(targetPort).append("\r\n");

        if (!upstreamUser.isEmpty()) {
            String auth = upstreamUser + ":" + upstreamPassword;
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            request.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n");
        }

        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read response
        StringBuilder response = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            response.append((char) ch);
            if (response.toString().endsWith("\r\n\r\n")) {
                break;
            }
        }

        String responseStr = response.toString();
        if (!responseStr.contains("200")) {
            Log.e(TAG, "HTTP CONNECT failed: " + responseStr);
            socket.close();
            return null;
        }

        return socket;
    }

    private void relay(Socket client, Socket upstream) {
        Thread clientToUpstream = new Thread(() -> {
            try {
                copy(client.getInputStream(), upstream.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(upstream);
            }
        }, "Relay-C2U");

        Thread upstreamToClient = new Thread(() -> {
            try {
                copy(upstream.getInputStream(), client.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(upstream);
            }
        }, "Relay-U2C");

        clientToUpstream.start();
        upstreamToClient.start();

        try {
            clientToUpstream.join();
            upstreamToClient.join();
        } catch (InterruptedException ignored) {
        }
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
            out.flush();
        }
    }

    private void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                throw new IOException("Unexpected end of stream");
            }
            offset += read;
        }
    }

    private void sendSocks5Error(OutputStream out, byte error) throws IOException {
        out.write(new byte[]{
                SOCKS5_VERSION, error, 0x00, SOCKS5_ATYP_IPV4,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        });
        out.flush();
    }

    private void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
