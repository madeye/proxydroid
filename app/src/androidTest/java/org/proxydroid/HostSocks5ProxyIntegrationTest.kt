package org.proxydroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Integration tests: from inside the Android emulator, drive raw SOCKS5
 * handshakes against host-side fake SOCKS5 proxies (NO_AUTH and RFC 1929
 * user/password).
 *
 * The emulator reaches the host loopback via the alias 10.0.2.2, so host
 * proxies bound to 0.0.0.0:<port> are reachable as 10.0.2.2:<port>.
 *
 * Overrides via instrumentation args, e.g.:
 *   ./gradlew connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.socksHost=10.0.2.2 \
 *     -Pandroid.testInstrumentationRunnerArguments.socksPort=1080 \
 *     -Pandroid.testInstrumentationRunnerArguments.socksAuthPort=1081 \
 *     -Pandroid.testInstrumentationRunnerArguments.socksAuthUser=alice \
 *     -Pandroid.testInstrumentationRunnerArguments.socksAuthPass=s3cret \
 *     -Pandroid.testInstrumentationRunnerArguments.targetHost=example.com \
 *     -Pandroid.testInstrumentationRunnerArguments.targetPort=80
 */
@RunWith(AndroidJUnit4::class)
class HostSocks5ProxyIntegrationTest {

    private val args = InstrumentationRegistry.getArguments()
    private val socksHost: String = args.getString("socksHost", "10.0.2.2")
    private val socksPort: Int = args.getString("socksPort", "1080").toInt()
    private val socksAuthPort: Int = args.getString("socksAuthPort", "1081").toInt()
    private val socksAuthUser: String = args.getString("socksAuthUser", "alice")
    private val socksAuthPass: String = args.getString("socksAuthPass", "s3cret")
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

            socks5Greet(out, input, user = null, pass = null)
            socks5ConnectByDomain(out, input, targetHost, targetPort)
            assertHttpGetSucceeds(out, input)
        }
    }

    @Test
    fun httpGetThroughHostSocks5ProxyWithBasicAuth() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(socksHost, socksAuthPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            socks5Greet(out, input, user = socksAuthUser, pass = socksAuthPass)
            socks5ConnectByDomain(out, input, targetHost, targetPort)
            assertHttpGetSucceeds(out, input)
        }
    }

    @Test
    fun socks5BasicAuthRejectsWrongCredentials() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(socksHost, socksAuthPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // Offer user/pass auth and supply credentials that don't match.
            out.write(byteArrayOf(0x05, 0x01, 0x02))
            out.flush()
            assertEquals("VER mismatch on greeting", 0x05, input.readUnsignedByte())
            assertEquals("Server should select USER/PASS method", 0x02, input.readUnsignedByte())

            val badUser = "nobody"
            val badPass = "definitelywrong"
            out.write(byteArrayOf(0x01))
            out.write(byteArrayOf(badUser.length.toByte()))
            out.write(badUser.toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(badPass.length.toByte()))
            out.write(badPass.toByteArray(Charsets.US_ASCII))
            out.flush()

            val subVer = input.readUnsignedByte()
            val status = input.readUnsignedByte()
            assertEquals("RFC 1929 sub-negotiation VER mismatch", 0x01, subVer)
            assertTrue(
                "Expected non-zero auth status (rejection), got $status",
                status != 0x00
            )
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun socks5Greet(
        out: DataOutputStream,
        input: DataInputStream,
        user: String?,
        pass: String?,
    ) {
        if (user == null) {
            // VER=5, NMETHODS=1, METHOD=0 (NO AUTH)
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            assertEquals("SOCKS version mismatch", 0x05, input.readUnsignedByte())
            assertEquals(
                "Proxy did not accept NO_AUTH (auth-less test variant)",
                0x00,
                input.readUnsignedByte()
            )
            return
        }

        // Offer NO_AUTH and USER/PASS, then perform RFC 1929 sub-negotiation.
        out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        out.flush()
        assertEquals("SOCKS version mismatch", 0x05, input.readUnsignedByte())
        val method = input.readUnsignedByte()
        assertEquals("Proxy did not select USER/PASS auth", 0x02, method)

        val u = user.toByteArray(Charsets.US_ASCII)
        val p = (pass ?: "").toByteArray(Charsets.US_ASCII)
        require(u.size in 1..255 && p.size in 0..255) { "creds out of range" }
        out.write(byteArrayOf(0x01))
        out.write(byteArrayOf(u.size.toByte()))
        out.write(u)
        out.write(byteArrayOf(p.size.toByte()))
        out.write(p)
        out.flush()

        assertEquals("RFC 1929 sub-negotiation VER mismatch", 0x01, input.readUnsignedByte())
        assertEquals(
            "RFC 1929 auth failed (status != 0)",
            0x00,
            input.readUnsignedByte()
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

        when (atyp) {
            0x01 -> input.skipBytes(4)
            0x03 -> {
                val len = input.readUnsignedByte()
                input.skipBytes(len)
            }
            0x04 -> input.skipBytes(16)
            else -> throw AssertionError("Unknown SOCKS ATYP=$atyp")
        }
        input.skipBytes(2) // BND.PORT
    }

    private fun assertHttpGetSucceeds(out: DataOutputStream, input: DataInputStream) {
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: ").append(targetHost).append("\r\n")
            append("User-Agent: ProxyDroid-IntegrationTest/1.0\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        out.write(request)
        out.flush()

        val statusLine = readHttpStatusLine(input)
        assertTrue(
            "Expected HTTP/1.x status line, got: $statusLine",
            statusLine.startsWith("HTTP/1.")
        )
        val parts = statusLine.split(' ', limit = 3)
        assertTrue("Malformed status line: $statusLine", parts.size >= 2)
        val code = parts[1].toIntOrNull() ?: -1
        assertTrue("Expected 2xx/3xx, got: $statusLine", code in 200..399)
    }

    private fun readHttpStatusLine(input: DataInputStream): String {
        val buf = StringBuilder()
        while (true) {
            val b = try { input.read() } catch (_: IOException) { -1 }
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
        }
        return buf.toString()
    }
}
