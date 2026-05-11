package org.proxydroid

import android.util.Base64
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
 * Integration tests: from inside the Android emulator, drive raw HTTP CONNECT
 * requests against host-side fake HTTP CONNECT proxies — both an auth-less
 * variant and one requiring `Proxy-Authorization: Basic <b64>`.
 *
 * Overrides via instrumentation args, e.g.:
 *   ./gradlew connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.httpProxyHost=10.0.2.2 \
 *     -Pandroid.testInstrumentationRunnerArguments.httpProxyPort=8081 \
 *     -Pandroid.testInstrumentationRunnerArguments.httpProxyAuthPort=8082 \
 *     -Pandroid.testInstrumentationRunnerArguments.httpProxyAuthUser=alice \
 *     -Pandroid.testInstrumentationRunnerArguments.httpProxyAuthPass=s3cret \
 *     -Pandroid.testInstrumentationRunnerArguments.httpsTargetHost=example.com \
 *     -Pandroid.testInstrumentationRunnerArguments.httpsTargetPort=443
 */
@RunWith(AndroidJUnit4::class)
class HostHttpConnectProxyIntegrationTest {

    private val args = InstrumentationRegistry.getArguments()
    private val proxyHost: String = args.getString("httpProxyHost", "10.0.2.2")
    private val proxyNoAuthPort: Int = args.getString("httpProxyPort", "8081").toInt()
    private val proxyAuthPort: Int = args.getString("httpProxyAuthPort", "8082").toInt()
    private val authUser: String = args.getString("httpProxyAuthUser", "alice")
    private val authPass: String = args.getString("httpProxyAuthPass", "s3cret")
    private val targetHost: String = args.getString("httpsTargetHost", "example.com")
    private val targetPort: Int = args.getString("httpsTargetPort", "443").toInt()
    private val connectTimeoutMs: Int = args.getString("connectTimeoutMs", "10000").toInt()
    private val readTimeoutMs: Int = args.getString("readTimeoutMs", "15000").toInt()

    @Test
    fun httpConnectThroughHostProxyNoAuth() {
        val (code, _) = doConnect(proxyNoAuthPort, creds = null)
        assertEquals("Expected 200 from auth-less CONNECT", 200, code)
    }

    @Test
    fun httpConnectBasicAuthSucceedsWithCorrectCredentials() {
        val (code, _) = doConnect(proxyAuthPort, creds = authUser to authPass)
        assertEquals("Expected 200 from auth'd CONNECT", 200, code)
    }

    @Test
    fun httpConnectBasicAuthRejectsWrongCredentials() {
        val (code, _) = doConnect(proxyAuthPort, creds = "nobody" to "definitelywrong")
        assertEquals(
            "Expected 407 Proxy Authentication Required from wrong creds",
            407,
            code,
        )
    }

    @Test
    fun httpConnectBasicAuthRejectsMissingCredentials() {
        val (code, _) = doConnect(proxyAuthPort, creds = null)
        assertEquals(
            "Expected 407 Proxy Authentication Required from no creds",
            407,
            code,
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Send a CONNECT, return (status_code, full_status_line). */
    private fun doConnect(port: Int, creds: Pair<String, String>?): Pair<Int, String> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxyHost, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            val hostPort = "$targetHost:$targetPort"
            val sb = StringBuilder()
                .append("CONNECT ").append(hostPort).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostPort).append("\r\n")
                .append("Proxy-Connection: keep-alive\r\n")
            if (creds != null) {
                val raw = "${creds.first}:${creds.second}".toByteArray(Charsets.UTF_8)
                val b64 = Base64.encodeToString(raw, Base64.NO_WRAP)
                sb.append("Proxy-Authorization: Basic ").append(b64).append("\r\n")
            }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.flush()

            val statusLine = readHttpStatusLine(input)
            assertTrue(
                "Expected HTTP/1.x status line, got: $statusLine",
                statusLine.startsWith("HTTP/1."),
            )
            val parts = statusLine.split(' ', limit = 3)
            assertTrue("Malformed status line: $statusLine", parts.size >= 2)
            val code = parts[1].toIntOrNull() ?: -1
            return code to statusLine
        }
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
