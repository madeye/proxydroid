package org.proxydroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Integration test: from inside the Android emulator, route an HTTP request through
 * a SOCKS5 proxy listening on the host machine at 0.0.0.0:1080.
 *
 * The emulator reaches the host loopback via the special alias 10.0.2.2, so a host
 * proxy bound to 0.0.0.0:1080 is reachable as 10.0.2.2:1080 from the device.
 *
 * Override at runtime with instrumentation args, e.g.:
 *   ./gradlew connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.socksHost=10.0.2.2 \
 *     -Pandroid.testInstrumentationRunnerArguments.socksPort=1080 \
 *     -Pandroid.testInstrumentationRunnerArguments.targetHost=example.com \
 *     -Pandroid.testInstrumentationRunnerArguments.targetPort=80
 */
@RunWith(AndroidJUnit4::class)
class HostSocks5ProxyIntegrationTest {

    private val args = InstrumentationRegistry.getArguments()
    private val socksHost: String = args.getString("socksHost", "10.0.2.2")
    private val socksPort: Int = args.getString("socksPort", "1080").toInt()
    private val targetHost: String = args.getString("targetHost", "example.com")
    private val targetPort: Int = args.getString("targetPort", "80").toInt()
    private val connectTimeoutMs: Int = args.getString("connectTimeoutMs", "10000").toInt()
    private val readTimeoutMs: Int = args.getString("readTimeoutMs", "15000").toInt()

    @Test
    fun httpGetThroughHostSocks5Proxy() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(socksHost, socksPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs

            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            socks5Greet(out, input)
            socks5ConnectByDomain(out, input, targetHost, targetPort)

            val request = buildString {
                append("GET / HTTP/1.1\r\n")
                append("Host: ").append(targetHost).append("\r\n")
                append("User-Agent: ProxyDroid-IntegrationTest/1.0\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            out.write(request)
            out.flush()

            val statusLine = readLine(input)
            assertTrue(
                "Expected HTTP/1.x status line, got: $statusLine",
                statusLine.startsWith("HTTP/1.")
            )
            val parts = statusLine.split(' ', limit = 3)
            assertTrue("Malformed status line: $statusLine", parts.size >= 2)
            val code = parts[1].toIntOrNull() ?: -1
            assertTrue(
                "Expected 2xx/3xx through proxy, got: $statusLine",
                code in 200..399
            )
        }
    }

    private fun socks5Greet(out: DataOutputStream, input: DataInputStream) {
        // VER=5, NMETHODS=1, METHOD=0 (NO AUTH)
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val ver = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        assertEquals("SOCKS version mismatch", 0x05, ver)
        assertEquals(
            "SOCKS proxy did not accept NO_AUTH (method=$method); test assumes unauthenticated proxy",
            0x00,
            method
        )
    }

    private fun socks5ConnectByDomain(
        out: DataOutputStream,
        input: DataInputStream,
        host: String,
        port: Int,
    ) {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        require(hostBytes.size <= 255) { "Hostname too long for SOCKS5: $host" }
        // VER=5, CMD=1 CONNECT, RSV=0, ATYP=3 DOMAINNAME, LEN, HOST, PORT(BE)
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
        out.write(hostBytes)
        out.writeShort(port)
        out.flush()

        val ver = input.readUnsignedByte()
        val rep = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        assertEquals("SOCKS reply version mismatch", 0x05, ver)
        assertEquals("SOCKS CONNECT failed with REP=$rep", 0x00, rep)

        // Drain BND.ADDR + BND.PORT so the stream sits at the start of payload.
        when (atyp) {
            0x01 -> input.skipBytes(4)            // IPv4
            0x03 -> {
                val len = input.readUnsignedByte()
                input.skipBytes(len)
            }
            0x04 -> input.skipBytes(16)           // IPv6
            else -> throw AssertionError("Unknown SOCKS ATYP=$atyp")
        }
        input.skipBytes(2) // BND.PORT
    }

    private fun readLine(input: DataInputStream): String {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
        }
        return buf.toString()
    }
}
