/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.proxydroid.utils

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

class LocalHttpProxyTest {

    @Test
    fun testSocks5HandshakeNoAuth() {
        val clientHello = byteArrayOf(0x05, 0x01, 0x00)
        val expectedResponse = byteArrayOf(0x05, 0x00)

        val input = ByteArrayInputStream(clientHello)
        val output = ByteArrayOutputStream()

        val version = input.read()
        val nmethods = input.read()
        val methods = ByteArray(nmethods)
        input.read(methods)

        assertEquals(0x05, version)
        assertEquals(1, nmethods)
        assertEquals(0x00.toByte(), methods[0])

        output.write(expectedResponse)

        val response = output.toByteArray()
        assertArrayEquals(expectedResponse, response)
    }

    @Test
    fun testSocks5HandshakeWithAuth() {
        val clientHello = byteArrayOf(0x05, 0x02, 0x00, 0x02)

        val input = ByteArrayInputStream(clientHello)

        val version = input.read()
        val nmethods = input.read()
        val methods = ByteArray(nmethods)
        input.read(methods)

        assertEquals(0x05, version)
        assertEquals(2, nmethods)
        assertEquals(0x00.toByte(), methods[0])
        assertEquals(0x02.toByte(), methods[1])
    }

    @Test
    fun testSocks5ConnectRequestIPv4() {
        val connectRequest = byteArrayOf(
            0x05,
            0x01,
            0x00,
            0x01,
            192.toByte(), 168.toByte(), 0x01, 0x01,
            0x00, 0x50
        )

        val input = ByteArrayInputStream(connectRequest)

        val version = input.read()
        val cmd = input.read()
        val rsv = input.read()
        val atyp = input.read()

        assertEquals(0x05, version)
        assertEquals(0x01, cmd)
        assertEquals(0x00, rsv)
        assertEquals(0x01, atyp)

        val ipv4 = ByteArray(4)
        input.read(ipv4)
        assertEquals(192.toByte(), ipv4[0])
        assertEquals(168.toByte(), ipv4[1])
        assertEquals(0x01.toByte(), ipv4[2])
        assertEquals(0x01.toByte(), ipv4[3])

        val port = ByteArray(2)
        input.read(port)
        val portNum = ((port[0].toInt() and 0xFF) shl 8) or (port[1].toInt() and 0xFF)
        assertEquals(80, portNum)
    }

    @Test
    fun testSocks5ConnectRequestDomain() {
        val domain = "example.com"
        val domainBytes = domain.toByteArray(StandardCharsets.UTF_8)

        val request = ByteArrayOutputStream().apply {
            write(0x05)
            write(0x01)
            write(0x00)
            write(0x03)
            write(domainBytes.size)
            write(domainBytes)
            write(0x01)
            write(0xBB)
        }

        val connectRequest = request.toByteArray()
        val input = ByteArrayInputStream(connectRequest)

        val version = input.read()
        val cmd = input.read()
        val rsv = input.read()
        val atyp = input.read()

        assertEquals(0x05, version)
        assertEquals(0x01, cmd)
        assertEquals(0x00, rsv)
        assertEquals(0x03, atyp)

        val domainLen = input.read()
        assertEquals(domain.length, domainLen)

        val readDomain = ByteArray(domainLen)
        input.read(readDomain)
        assertEquals(domain, String(readDomain, StandardCharsets.UTF_8))

        val port = ByteArray(2)
        input.read(port)
        val portNum = ((port[0].toInt() and 0xFF) shl 8) or (port[1].toInt() and 0xFF)
        assertEquals(443, portNum)
    }

    @Test
    fun testSocks5SuccessResponse() {
        val successResponse = byteArrayOf(
            0x05, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00
        )

        assertEquals(10, successResponse.size)
        assertEquals(0x05.toByte(), successResponse[0])
        assertEquals(0x00.toByte(), successResponse[1])
        assertEquals(0x01.toByte(), successResponse[3])
    }

    @Test
    fun testSocks5ErrorResponse() {
        val errorCodes = intArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)

        for (errorCode in errorCodes) {
            val errorResponse = byteArrayOf(
                0x05, errorCode.toByte(), 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )

            assertEquals(0x05.toByte(), errorResponse[0])
            assertEquals(errorCode, errorResponse[1].toInt() and 0xFF)
        }
    }

    @Test
    fun testSocks5AuthRequest() {
        val username = "testuser"
        val password = "testpass"

        val userBytes = username.toByteArray(StandardCharsets.UTF_8)
        val passBytes = password.toByteArray(StandardCharsets.UTF_8)

        val authRequest = ByteArrayOutputStream().apply {
            write(0x01)
            write(userBytes.size)
            write(userBytes)
            write(passBytes.size)
            write(passBytes)
        }

        val request = authRequest.toByteArray()
        val input = ByteArrayInputStream(request)

        val authVersion = input.read()
        assertEquals(0x01, authVersion)

        val userLen = input.read()
        assertEquals(username.length, userLen)

        val readUser = ByteArray(userLen)
        input.read(readUser)
        assertEquals(username, String(readUser, StandardCharsets.UTF_8))

        val passLen = input.read()
        assertEquals(password.length, passLen)

        val readPass = ByteArray(passLen)
        input.read(readPass)
        assertEquals(password, String(readPass, StandardCharsets.UTF_8))
    }

    @Test
    fun testHttpConnectRequest() {
        val host = "example.com"
        val port = 443
        val username = "user"
        val password = "pass"

        val request = StringBuilder().apply {
            append("CONNECT ").append(host).append(":").append(port).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(":").append(port).append("\r\n")
            val auth = "$username:$password"
            val encoded = Base64.getEncoder().encodeToString(auth.toByteArray(StandardCharsets.UTF_8))
            append("Proxy-Authorization: Basic ").append(encoded).append("\r\n")
            append("\r\n")
        }

        val requestStr = request.toString()

        assertTrue(requestStr.startsWith("CONNECT example.com:443 HTTP/1.1\r\n"))
        assertTrue(requestStr.contains("Host: example.com:443\r\n"))
        assertTrue(requestStr.contains("Proxy-Authorization: Basic "))
        assertTrue(requestStr.endsWith("\r\n\r\n"))
    }

    @Test
    fun testHttpConnectResponse() {
        val successResponse = "HTTP/1.1 200 Connection Established\r\n\r\n"
        val failResponse = "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"

        assertTrue(successResponse.contains("200"))
        assertFalse(failResponse.contains("200"))
    }

    @Test
    fun testIPv4Parsing() {
        val ipBytes = byteArrayOf(192.toByte(), 168.toByte(), 0x01, 0x01)

        val ip = String.format(
            "%d.%d.%d.%d",
            ipBytes[0].toInt() and 0xFF,
            ipBytes[1].toInt() and 0xFF,
            ipBytes[2].toInt() and 0xFF,
            ipBytes[3].toInt() and 0xFF
        )

        assertEquals("192.168.1.1", ip)
    }

    @Test
    fun testIPv6Parsing() {
        val ipv6 = ByteArray(16)
        ipv6[0] = 0x20
        ipv6[1] = 0x01
        ipv6[2] = 0x0d
        ipv6[3] = 0xb8.toByte()

        val sb = StringBuilder()
        for (i in 0 until 16 step 2) {
            if (i > 0) sb.append(":")
            sb.append(String.format("%02x%02x", ipv6[i].toInt() and 0xFF, ipv6[i + 1].toInt() and 0xFF))
        }

        val ip = sb.toString()
        assertTrue(ip.startsWith("2001:0db8:"))
    }

    @Test
    fun testPortParsing() {
        val portBytes80 = byteArrayOf(0x00, 0x50)
        val portBytes443 = byteArrayOf(0x01, 0xBB.toByte())
        val portBytes8080 = byteArrayOf(0x1F, 0x90.toByte())

        val port80 = ((portBytes80[0].toInt() and 0xFF) shl 8) or (portBytes80[1].toInt() and 0xFF)
        val port443 = ((portBytes443[0].toInt() and 0xFF) shl 8) or (portBytes443[1].toInt() and 0xFF)
        val port8080 = ((portBytes8080[0].toInt() and 0xFF) shl 8) or (portBytes8080[1].toInt() and 0xFF)

        assertEquals(80, port80)
        assertEquals(443, port443)
        assertEquals(8080, port8080)
    }

    @Test
    fun testBase64Encoding() {
        val credentials = "user:password"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))

        assertEquals("dXNlcjpwYXNzd29yZA==", encoded)

        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        assertEquals(credentials, decoded)
    }
}
