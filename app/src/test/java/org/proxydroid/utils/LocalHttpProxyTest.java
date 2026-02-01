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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Tests for local HTTP proxy functionality.
 * Tests SOCKS5 protocol handling and HTTP CONNECT tunneling.
 */
public class LocalHttpProxyTest {

    private static final int SOCKS5_VERSION = 0x05;
    private static final int SOCKS5_CMD_CONNECT = 0x01;
    private static final int SOCKS5_ATYP_IPV4 = 0x01;
    private static final int SOCKS5_ATYP_DOMAIN = 0x03;

    private MockHttpProxy mockHttpProxy;
    private TestLocalSocksServer testSocksServer;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        executor = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() throws Exception {
        if (mockHttpProxy != null) {
            mockHttpProxy.stop();
        }
        if (testSocksServer != null) {
            testSocksServer.stop();
        }
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testSocks5Handshake() throws Exception {
        // Create a mock HTTP proxy
        mockHttpProxy = new MockHttpProxy(true);
        mockHttpProxy.start();

        // Create a local SOCKS5 server that forwards to HTTP proxy
        testSocksServer = new TestLocalSocksServer(
                "127.0.0.1", mockHttpProxy.getPort(), null, null);
        testSocksServer.start();

        // Connect as SOCKS5 client
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", testSocksServer.getPort()), 5000);
        client.setSoTimeout(5000);

        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Send SOCKS5 greeting
            out.write(new byte[]{SOCKS5_VERSION, 0x01, 0x00}); // Version 5, 1 method, no auth
            out.flush();

            // Read response
            byte[] response = new byte[2];
            int read = in.read(response);
            assertEquals(2, read);
            assertEquals(SOCKS5_VERSION, response[0]);
            assertEquals(0x00, response[1]); // No auth required

        } finally {
            client.close();
        }
    }

    @Test
    public void testSocks5ConnectIPv4() throws Exception {
        // Create a mock HTTP proxy
        mockHttpProxy = new MockHttpProxy(true);
        mockHttpProxy.start();

        // Create a local SOCKS5 server
        testSocksServer = new TestLocalSocksServer(
                "127.0.0.1", mockHttpProxy.getPort(), null, null);
        testSocksServer.start();

        // Connect as SOCKS5 client
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", testSocksServer.getPort()), 5000);
        client.setSoTimeout(5000);

        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // SOCKS5 handshake
            out.write(new byte[]{SOCKS5_VERSION, 0x01, 0x00});
            out.flush();
            byte[] handshake = new byte[2];
            in.read(handshake);
            assertEquals(SOCKS5_VERSION, handshake[0]);

            // Send CONNECT request to 93.184.216.34:80 (example.com)
            byte[] connectReq = new byte[]{
                    SOCKS5_VERSION,       // Version
                    SOCKS5_CMD_CONNECT,   // Connect command
                    0x00,                 // Reserved
                    SOCKS5_ATYP_IPV4,     // IPv4 address type
                    93, (byte) 184, (byte) 216, 34,  // IP address
                    0x00, 0x50            // Port 80
            };
            out.write(connectReq);
            out.flush();

            // Read response
            byte[] response = new byte[10];
            int read = in.read(response);
            assertEquals(10, read);
            assertEquals(SOCKS5_VERSION, response[0]);
            assertEquals(0x00, response[1]); // Success

            // Verify HTTP proxy received CONNECT request
            assertTrue(mockHttpProxy.getLastRequest().contains("CONNECT 93.184.216.34:80"));

        } finally {
            client.close();
        }
    }

    @Test
    public void testSocks5ConnectDomain() throws Exception {
        // Create a mock HTTP proxy
        mockHttpProxy = new MockHttpProxy(true);
        mockHttpProxy.start();

        // Create a local SOCKS5 server
        testSocksServer = new TestLocalSocksServer(
                "127.0.0.1", mockHttpProxy.getPort(), null, null);
        testSocksServer.start();

        // Connect as SOCKS5 client
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", testSocksServer.getPort()), 5000);
        client.setSoTimeout(5000);

        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // SOCKS5 handshake
            out.write(new byte[]{SOCKS5_VERSION, 0x01, 0x00});
            out.flush();
            byte[] handshake = new byte[2];
            in.read(handshake);

            // Send CONNECT request to example.com:443
            String domain = "example.com";
            byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
            byte[] connectReq = new byte[4 + 1 + domainBytes.length + 2];
            connectReq[0] = SOCKS5_VERSION;
            connectReq[1] = SOCKS5_CMD_CONNECT;
            connectReq[2] = 0x00;
            connectReq[3] = SOCKS5_ATYP_DOMAIN;
            connectReq[4] = (byte) domainBytes.length;
            System.arraycopy(domainBytes, 0, connectReq, 5, domainBytes.length);
            connectReq[5 + domainBytes.length] = 0x01;     // Port 443 high byte
            connectReq[6 + domainBytes.length] = (byte) 0xBB; // Port 443 low byte

            out.write(connectReq);
            out.flush();

            // Read response
            byte[] response = new byte[10];
            int read = in.read(response);
            assertEquals(10, read);
            assertEquals(SOCKS5_VERSION, response[0]);
            assertEquals(0x00, response[1]); // Success

            // Verify HTTP proxy received CONNECT request
            assertTrue(mockHttpProxy.getLastRequest().contains("CONNECT example.com:443"));

        } finally {
            client.close();
        }
    }

    @Test
    public void testHttpProxyAuthentication() throws Exception {
        // Create a mock HTTP proxy requiring auth
        mockHttpProxy = new MockHttpProxy(true);
        mockHttpProxy.setRequireAuth("testuser", "testpass");
        mockHttpProxy.start();

        // Create a local SOCKS5 server with credentials
        testSocksServer = new TestLocalSocksServer(
                "127.0.0.1", mockHttpProxy.getPort(), "testuser", "testpass");
        testSocksServer.start();

        // Connect as SOCKS5 client
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", testSocksServer.getPort()), 5000);
        client.setSoTimeout(5000);

        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // SOCKS5 handshake
            out.write(new byte[]{SOCKS5_VERSION, 0x01, 0x00});
            out.flush();
            byte[] handshake = new byte[2];
            in.read(handshake);

            // Send CONNECT request
            String domain = "secure.example.com";
            byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
            byte[] connectReq = new byte[4 + 1 + domainBytes.length + 2];
            connectReq[0] = SOCKS5_VERSION;
            connectReq[1] = SOCKS5_CMD_CONNECT;
            connectReq[2] = 0x00;
            connectReq[3] = SOCKS5_ATYP_DOMAIN;
            connectReq[4] = (byte) domainBytes.length;
            System.arraycopy(domainBytes, 0, connectReq, 5, domainBytes.length);
            connectReq[5 + domainBytes.length] = 0x01;
            connectReq[6 + domainBytes.length] = (byte) 0xBB;

            out.write(connectReq);
            out.flush();

            // Read response
            byte[] response = new byte[10];
            int read = in.read(response);
            assertEquals(10, read);
            assertEquals(SOCKS5_VERSION, response[0]);
            assertEquals(0x00, response[1]); // Success

            // Verify HTTP proxy received auth header
            String request = mockHttpProxy.getLastRequest();
            assertTrue(request.contains("Proxy-Authorization: Basic"));
            String expectedAuth = Base64.getEncoder().encodeToString("testuser:testpass".getBytes(StandardCharsets.UTF_8));
            assertTrue(request.contains(expectedAuth));

        } finally {
            client.close();
        }
    }

    @Test
    public void testHttpProxyConnectionFailure() throws Exception {
        // Create a mock HTTP proxy that rejects connections
        mockHttpProxy = new MockHttpProxy(false); // Will return 403
        mockHttpProxy.start();

        // Create a local SOCKS5 server
        testSocksServer = new TestLocalSocksServer(
                "127.0.0.1", mockHttpProxy.getPort(), null, null);
        testSocksServer.start();

        // Connect as SOCKS5 client
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", testSocksServer.getPort()), 5000);
        client.setSoTimeout(5000);

        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // SOCKS5 handshake
            out.write(new byte[]{SOCKS5_VERSION, 0x01, 0x00});
            out.flush();
            byte[] handshake = new byte[2];
            in.read(handshake);

            // Send CONNECT request
            byte[] connectReq = new byte[]{
                    SOCKS5_VERSION,
                    SOCKS5_CMD_CONNECT,
                    0x00,
                    SOCKS5_ATYP_IPV4,
                    10, 0, 0, 1,
                    0x00, 0x50
            };
            out.write(connectReq);
            out.flush();

            // Read response - should indicate failure
            byte[] response = new byte[10];
            int read = in.read(response);
            assertEquals(10, read);
            assertEquals(SOCKS5_VERSION, response[0]);
            assertEquals(0x05, response[1]); // Connection refused

        } finally {
            client.close();
        }
    }

    @Test
    public void testDataRelay() throws Exception {
        // Create a mock HTTP proxy that echoes data
        mockHttpProxy = new MockHttpProxy(true);
        mockHttpProxy.setEchoMode(true);
        mockHttpProxy.start();

        // Create a local SOCKS5 server
        testSocksServer = new TestLocalSocksServer(
                "127.0.0.1", mockHttpProxy.getPort(), null, null);
        testSocksServer.start();

        // Connect as SOCKS5 client
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", testSocksServer.getPort()), 5000);
        client.setSoTimeout(5000);

        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // SOCKS5 handshake
            out.write(new byte[]{SOCKS5_VERSION, 0x01, 0x00});
            out.flush();
            byte[] handshake = new byte[2];
            in.read(handshake);

            // Send CONNECT request
            byte[] connectReq = new byte[]{
                    SOCKS5_VERSION,
                    SOCKS5_CMD_CONNECT,
                    0x00,
                    SOCKS5_ATYP_IPV4,
                    8, 8, 8, 8,
                    0x00, 0x50
            };
            out.write(connectReq);
            out.flush();

            // Read connect response
            byte[] response = new byte[10];
            in.read(response);
            assertEquals(0x00, response[1]); // Success

            // Send test data
            String testData = "Hello, Proxy!";
            out.write(testData.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Read echoed data
            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            String echoed = new String(buffer, 0, read, StandardCharsets.UTF_8);
            assertEquals(testData, echoed);

        } finally {
            client.close();
        }
    }

    @Test
    public void testServerStartStop() throws Exception {
        mockHttpProxy = new MockHttpProxy(true);
        mockHttpProxy.start();

        testSocksServer = new TestLocalSocksServer(
                "127.0.0.1", mockHttpProxy.getPort(), null, null);

        assertFalse(testSocksServer.isRunning());

        assertTrue(testSocksServer.start());
        assertTrue(testSocksServer.isRunning());
        assertTrue(testSocksServer.getPort() > 0);

        testSocksServer.stop();
        assertFalse(testSocksServer.isRunning());
    }

    @Test
    public void testConcurrentConnections() throws Exception {
        mockHttpProxy = new MockHttpProxy(true);
        mockHttpProxy.start();

        testSocksServer = new TestLocalSocksServer(
                "127.0.0.1", mockHttpProxy.getPort(), null, null);
        testSocksServer.start();

        int numClients = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numClients);
        AtomicBoolean allSucceeded = new AtomicBoolean(true);

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Socket client = new Socket();
                    client.connect(new InetSocketAddress("127.0.0.1", testSocksServer.getPort()), 5000);
                    client.setSoTimeout(5000);

                    try {
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream();

                        // SOCKS5 handshake
                        out.write(new byte[]{SOCKS5_VERSION, 0x01, 0x00});
                        out.flush();
                        byte[] handshake = new byte[2];
                        in.read(handshake);

                        if (handshake[0] != SOCKS5_VERSION || handshake[1] != 0x00) {
                            allSucceeded.set(false);
                        }
                    } finally {
                        client.close();
                    }
                } catch (Exception e) {
                    allSucceeded.set(false);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        assertTrue(allSucceeded.get());
    }

    /**
     * Mock HTTP proxy server for testing
     */
    private static class MockHttpProxy {
        private ServerSocket serverSocket;
        private volatile boolean running = false;
        private Thread serverThread;
        private boolean acceptConnections;
        private String requiredUsername;
        private String requiredPassword;
        private boolean echoMode = false;
        private AtomicReference<String> lastRequest = new AtomicReference<>("");
        private ExecutorService executor = Executors.newCachedThreadPool();

        public MockHttpProxy(boolean acceptConnections) {
            this.acceptConnections = acceptConnections;
        }

        public void setRequireAuth(String username, String password) {
            this.requiredUsername = username;
            this.requiredPassword = password;
        }

        public void setEchoMode(boolean echo) {
            this.echoMode = echo;
        }

        public void start() throws IOException {
            serverSocket = new ServerSocket(0);
            running = true;

            serverThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.submit(() -> handleClient(client));
                    } catch (IOException e) {
                        if (running) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            serverThread.start();
        }

        private void handleClient(Socket client) {
            try {
                client.setSoTimeout(5000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                // Read HTTP request
                StringBuilder request = new StringBuilder();
                int ch;
                while ((ch = in.read()) != -1) {
                    request.append((char) ch);
                    if (request.toString().endsWith("\r\n\r\n")) {
                        break;
                    }
                }

                lastRequest.set(request.toString());

                // Check authentication if required
                if (requiredUsername != null) {
                    String expectedAuth = Base64.getEncoder().encodeToString(
                            (requiredUsername + ":" + requiredPassword).getBytes(StandardCharsets.UTF_8));
                    if (!request.toString().contains(expectedAuth)) {
                        out.write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".getBytes());
                        out.flush();
                        client.close();
                        return;
                    }
                }

                if (acceptConnections) {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                    out.flush();

                    if (echoMode) {
                        // Echo any received data back
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            out.flush();
                        }
                    }
                } else {
                    out.write("HTTP/1.1 403 Forbidden\r\n\r\n".getBytes());
                    out.flush();
                }
            } catch (IOException e) {
                // Connection closed
            } finally {
                try {
                    client.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        public void stop() {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            if (serverThread != null) {
                serverThread.interrupt();
            }
            executor.shutdownNow();
        }

        public int getPort() {
            return serverSocket.getLocalPort();
        }

        public String getLastRequest() {
            return lastRequest.get();
        }
    }

    /**
     * Test local SOCKS5 server (pure Java, no Android dependencies)
     */
    private static class TestLocalSocksServer {
        private static final int SOCKS5_VERSION = 0x05;
        private static final int SOCKS5_CMD_CONNECT = 0x01;
        private static final int SOCKS5_ATYP_IPV4 = 0x01;
        private static final int SOCKS5_ATYP_DOMAIN = 0x03;
        private static final int SOCKS5_ATYP_IPV6 = 0x04;

        private final String remoteHost;
        private final int remotePort;
        private final String username;
        private final String password;

        private ServerSocket serverSocket;
        private ExecutorService executor;
        private volatile boolean running = false;
        private Thread serverThread;
        private int localPort;

        public TestLocalSocksServer(String remoteHost, int remotePort,
                                    String username, String password) {
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.username = username;
            this.password = password;
        }

        public boolean start() {
            if (running) {
                return true;
            }

            try {
                serverSocket = new ServerSocket(0);
                localPort = serverSocket.getLocalPort();
                executor = Executors.newCachedThreadPool();
                running = true;

                serverThread = new Thread(() -> {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        try {
                            Socket clientSocket = serverSocket.accept();
                            executor.submit(() -> handleClient(clientSocket));
                        } catch (IOException e) {
                            if (running) {
                                e.printStackTrace();
                            }
                        }
                    }
                });

                serverThread.start();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        public void stop() {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // ignore
                }
                serverSocket = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            if (serverThread != null) {
                serverThread.interrupt();
                serverThread = null;
            }
        }

        public int getPort() {
            return localPort;
        }

        public boolean isRunning() {
            return running;
        }

        private void handleClient(Socket clientSocket) {
            try {
                clientSocket.setSoTimeout(5000);
                handleSocks5Connection(clientSocket);
            } catch (IOException e) {
                // Connection closed
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        private void handleSocks5Connection(Socket clientSocket) throws IOException {
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // SOCKS5 handshake
            int version = in.read();
            if (version != SOCKS5_VERSION) {
                return;
            }

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
                out.write(new byte[]{SOCKS5_VERSION, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                return;
            }

            in.read(); // Reserved

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
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", ipv6[i] & 0xFF, ipv6[i + 1] & 0xFF));
                    }
                    destHost = sb.toString();
                    break;
                default:
                    out.write(new byte[]{SOCKS5_VERSION, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                    out.flush();
                    return;
            }

            destPort = (in.read() << 8) | in.read();

            // Connect through HTTP proxy
            Socket proxySocket = null;
            try {
                proxySocket = connectThroughHttpProxy(destHost, destPort);

                // Send success response
                out.write(new byte[]{
                        SOCKS5_VERSION, 0x00, 0x00, 0x01,
                        0, 0, 0, 0,
                        0, 0
                });
                out.flush();

                // Relay data
                relayData(clientSocket, proxySocket);

            } catch (IOException e) {
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
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
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
            if (!responseStr.contains(" 200 ")) {
                proxySocket.close();
                throw new IOException("Proxy connection failed: " + responseStr.split("\r\n")[0]);
            }

            return proxySocket;
        }

        private void relayData(Socket client, Socket proxy) {
            Thread clientToProxy = new Thread(() -> {
                try {
                    copyStream(client.getInputStream(), proxy.getOutputStream());
                } catch (IOException e) {
                    // Connection closed
                }
            });

            Thread proxyToClient = new Thread(() -> {
                try {
                    copyStream(proxy.getInputStream(), client.getOutputStream());
                } catch (IOException e) {
                    // Connection closed
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
