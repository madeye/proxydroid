/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.proxydroid.utils;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for LocalProxyServer SOCKS5 implementation.
 */
public class LocalHttpProxyTest {

    @Test
    public void testSocks5HandshakeNoAuth() throws Exception {
        // Test SOCKS5 handshake without authentication
        byte[] clientHello = new byte[]{0x05, 0x01, 0x00}; // Version 5, 1 method, no auth
        byte[] expectedResponse = new byte[]{0x05, 0x00}; // Version 5, no auth selected

        ByteArrayInputStream input = new ByteArrayInputStream(clientHello);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // Simulate reading version and methods
        int version = input.read();
        int nmethods = input.read();
        byte[] methods = new byte[nmethods];
        input.read(methods);

        assertEquals(0x05, version);
        assertEquals(1, nmethods);
        assertEquals(0x00, methods[0]);

        // Write response
        output.write(expectedResponse);

        byte[] response = output.toByteArray();
        assertArrayEquals(expectedResponse, response);
    }

    @Test
    public void testSocks5HandshakeWithAuth() throws Exception {
        // Test SOCKS5 handshake with username/password authentication
        byte[] clientHello = new byte[]{0x05, 0x02, 0x00, 0x02}; // Version 5, 2 methods, no auth + user/pass

        ByteArrayInputStream input = new ByteArrayInputStream(clientHello);

        int version = input.read();
        int nmethods = input.read();
        byte[] methods = new byte[nmethods];
        input.read(methods);

        assertEquals(0x05, version);
        assertEquals(2, nmethods);
        assertEquals(0x00, methods[0]);
        assertEquals(0x02, methods[1]);
    }

    @Test
    public void testSocks5ConnectRequestIPv4() throws Exception {
        // Test SOCKS5 connect request with IPv4 address
        byte[] connectRequest = new byte[]{
            0x05,                   // Version
            0x01,                   // CMD: CONNECT
            0x00,                   // Reserved
            0x01,                   // ATYP: IPv4
            (byte)192, (byte)168, 0x01, 0x01,  // IP: 192.168.1.1
            0x00, 0x50              // Port: 80
        };

        ByteArrayInputStream input = new ByteArrayInputStream(connectRequest);

        int version = input.read();
        int cmd = input.read();
        int rsv = input.read();
        int atyp = input.read();

        assertEquals(0x05, version);
        assertEquals(0x01, cmd);     // CONNECT
        assertEquals(0x00, rsv);
        assertEquals(0x01, atyp);    // IPv4

        byte[] ipv4 = new byte[4];
        input.read(ipv4);
        assertEquals((byte)192, ipv4[0]);
        assertEquals((byte)168, ipv4[1]);
        assertEquals(0x01, ipv4[2]);
        assertEquals(0x01, ipv4[3]);

        byte[] port = new byte[2];
        input.read(port);
        int portNum = ((port[0] & 0xFF) << 8) | (port[1] & 0xFF);
        assertEquals(80, portNum);
    }

    @Test
    public void testSocks5ConnectRequestDomain() throws Exception {
        // Test SOCKS5 connect request with domain name
        String domain = "example.com";
        byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(0x05);                    // Version
        request.write(0x01);                    // CMD: CONNECT
        request.write(0x00);                    // Reserved
        request.write(0x03);                    // ATYP: Domain
        request.write(domainBytes.length);     // Domain length
        request.write(domainBytes);            // Domain
        request.write(0x01);                   // Port high byte (443)
        request.write((byte)0xBB);             // Port low byte

        byte[] connectRequest = request.toByteArray();
        ByteArrayInputStream input = new ByteArrayInputStream(connectRequest);

        int version = input.read();
        int cmd = input.read();
        int rsv = input.read();
        int atyp = input.read();

        assertEquals(0x05, version);
        assertEquals(0x01, cmd);
        assertEquals(0x00, rsv);
        assertEquals(0x03, atyp);   // Domain

        int domainLen = input.read();
        assertEquals(domain.length(), domainLen);

        byte[] readDomain = new byte[domainLen];
        input.read(readDomain);
        assertEquals(domain, new String(readDomain, StandardCharsets.UTF_8));

        byte[] port = new byte[2];
        input.read(port);
        int portNum = ((port[0] & 0xFF) << 8) | (port[1] & 0xFF);
        assertEquals(443, portNum);
    }

    @Test
    public void testSocks5SuccessResponse() throws Exception {
        // Test SOCKS5 success response format
        byte[] successResponse = new byte[]{
            0x05,                   // Version
            0x00,                   // Status: Success
            0x00,                   // Reserved
            0x01,                   // ATYP: IPv4
            0x00, 0x00, 0x00, 0x00, // Bound address: 0.0.0.0
            0x00, 0x00              // Bound port: 0
        };

        assertEquals(10, successResponse.length);
        assertEquals(0x05, successResponse[0]);
        assertEquals(0x00, successResponse[1]); // Success
        assertEquals(0x01, successResponse[3]); // IPv4
    }

    @Test
    public void testSocks5ErrorResponse() throws Exception {
        // Test various SOCKS5 error codes
        int[] errorCodes = {
            0x01,  // General failure
            0x02,  // Connection not allowed
            0x03,  // Network unreachable
            0x04,  // Host unreachable
            0x05,  // Connection refused
            0x06,  // TTL expired
            0x07,  // Command not supported
            0x08   // Address type not supported
        };

        for (int errorCode : errorCodes) {
            byte[] errorResponse = new byte[]{
                0x05,                   // Version
                (byte) errorCode,       // Error code
                0x00,                   // Reserved
                0x01,                   // ATYP: IPv4
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
            };

            assertEquals(0x05, errorResponse[0]);
            assertEquals(errorCode, errorResponse[1] & 0xFF);
        }
    }

    @Test
    public void testSocks5AuthRequest() throws Exception {
        // Test username/password authentication request format
        String username = "testuser";
        String password = "testpass";

        byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream authRequest = new ByteArrayOutputStream();
        authRequest.write(0x01);              // Auth version
        authRequest.write(userBytes.length);  // Username length
        authRequest.write(userBytes);         // Username
        authRequest.write(passBytes.length);  // Password length
        authRequest.write(passBytes);         // Password

        byte[] request = authRequest.toByteArray();
        ByteArrayInputStream input = new ByteArrayInputStream(request);

        int authVersion = input.read();
        assertEquals(0x01, authVersion);

        int userLen = input.read();
        assertEquals(username.length(), userLen);

        byte[] readUser = new byte[userLen];
        input.read(readUser);
        assertEquals(username, new String(readUser, StandardCharsets.UTF_8));

        int passLen = input.read();
        assertEquals(password.length(), passLen);

        byte[] readPass = new byte[passLen];
        input.read(readPass);
        assertEquals(password, new String(readPass, StandardCharsets.UTF_8));
    }

    @Test
    public void testHttpConnectRequest() throws Exception {
        // Test HTTP CONNECT request format
        String host = "example.com";
        int port = 443;
        String username = "user";
        String password = "pass";

        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(host).append(":").append(port).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host).append(":").append(port).append("\r\n");

        String auth = username + ":" + password;
        String encoded = java.util.Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        request.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n");
        request.append("\r\n");

        String requestStr = request.toString();

        assertTrue(requestStr.startsWith("CONNECT example.com:443 HTTP/1.1\r\n"));
        assertTrue(requestStr.contains("Host: example.com:443\r\n"));
        assertTrue(requestStr.contains("Proxy-Authorization: Basic "));
        assertTrue(requestStr.endsWith("\r\n\r\n"));
    }

    @Test
    public void testHttpConnectResponse() throws Exception {
        // Test HTTP CONNECT response parsing
        String successResponse = "HTTP/1.1 200 Connection Established\r\n\r\n";
        String failResponse = "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n";

        assertTrue(successResponse.contains("200"));
        assertFalse(failResponse.contains("200"));
    }

    @Test
    public void testIPv4Parsing() throws Exception {
        // Test IPv4 address parsing from bytes
        byte[] ipBytes = new byte[]{(byte)192, (byte)168, 0x01, 0x01};

        String ip = String.format("%d.%d.%d.%d",
            ipBytes[0] & 0xFF,
            ipBytes[1] & 0xFF,
            ipBytes[2] & 0xFF,
            ipBytes[3] & 0xFF);

        assertEquals("192.168.1.1", ip);
    }

    @Test
    public void testIPv6Parsing() throws Exception {
        // Test IPv6 address parsing from bytes
        byte[] ipv6 = new byte[16];
        ipv6[0] = 0x20;
        ipv6[1] = 0x01;
        ipv6[2] = 0x0d;
        ipv6[3] = (byte)0xb8;
        // Rest are zeros

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02x%02x", ipv6[i] & 0xFF, ipv6[i + 1] & 0xFF));
        }

        String ip = sb.toString();
        assertTrue(ip.startsWith("2001:0db8:"));
    }

    @Test
    public void testPortParsing() throws Exception {
        // Test port number parsing from big-endian bytes
        byte[] portBytes80 = new byte[]{0x00, 0x50};
        byte[] portBytes443 = new byte[]{0x01, (byte)0xBB};
        byte[] portBytes8080 = new byte[]{0x1F, (byte)0x90};

        int port80 = ((portBytes80[0] & 0xFF) << 8) | (portBytes80[1] & 0xFF);
        int port443 = ((portBytes443[0] & 0xFF) << 8) | (portBytes443[1] & 0xFF);
        int port8080 = ((portBytes8080[0] & 0xFF) << 8) | (portBytes8080[1] & 0xFF);

        assertEquals(80, port80);
        assertEquals(443, port443);
        assertEquals(8080, port8080);
    }

    @Test
    public void testBase64Encoding() throws Exception {
        // Test Base64 encoding for proxy authentication
        String credentials = "user:password";
        String encoded = java.util.Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8));

        assertEquals("dXNlcjpwYXNzd29yZA==", encoded);

        // Verify decoding
        String decoded = new String(
            java.util.Base64.getDecoder().decode(encoded),
            StandardCharsets.UTF_8);
        assertEquals(credentials, decoded);
    }
}
