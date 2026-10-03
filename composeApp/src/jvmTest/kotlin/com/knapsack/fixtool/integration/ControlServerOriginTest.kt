package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The control port answers tools on this machine, not web pages.**
 *
 * It binds loopback, which keeps other machines out and nothing else: a browser on this machine reaches
 * `127.0.0.1` for any page that asks it to. With no token set (the default), a page could POST a text/plain
 * body to `/send` with `resolve: true`, which sends orders and evaluates Kotlin, and the browser would deliver
 * it without a preflight because a text/plain POST is a "simple" request. The page never reads the answer,
 * and it does not need to.
 *
 * A browser says where a request came from in three ways, and each test below is one of them: `Origin` on
 * anything but a plain GET, `Sec-Fetch-Site` on everything a modern browser sends, and a `Host` naming the
 * page's own domain when it rebinds that domain to this machine. Tools (curl, the MCP bridge, an agent's
 * HTTP client) send none of these, and the last tests pin that they still get in.
 */
class ControlServerOriginTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-origin-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        port = TestPorts.free()
        server = ControlServer(port, viewModel, windowProvider = { emptyList() }, token = null)
        server.start()
    }

    @After
    fun cleanup() {
        server.stop()
        testDir.deleteRecursively()
    }

    private fun get(path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "text/plain")
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * The status line for a GET carrying [host] as its Host header, over a bare socket. The JDK's own client
     * will not let a caller set Host, which is the one header a rebinding page cannot help but send.
     */
    private fun statusWithHost(host: String, path: String = "/health"): Int =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            val request = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val statusLine =
                socket
                    .getInputStream()
                    .bufferedReader()
                    .readLine()
                    .orEmpty()
            statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("no status line: '$statusLine'")
        }

    // ------------------------------------------------------------------ what a page sends

    @Test
    fun `a request a page on another site sends is refused`() {
        val response = post("/send/all", """{"raw":"35=D|11=X|"}""", "Origin" to "https://evil.example")

        assertEquals(403, response.statusCode(), "a page on evil.example drove /send/all: ${response.body()}")
        assertTrue("\"status\":\"error\"" in response.body(), response.body())
    }

    @Test
    fun `the MCP endpoint refuses a page on another site too`() {
        val response =
            post(
                "/mcp",
                """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
                "Origin" to "https://evil.example",
            )

        assertEquals(403, response.statusCode(), response.body())
    }

    @Test
    fun `an Origin of null, which a sandboxed frame or a local file sends, is refused`() {
        assertEquals(403, get("/health", "Origin" to "null").statusCode())
    }

    @Test
    fun `a plain GET a page triggers without an Origin is refused by its fetch metadata`() {
        // What an <img src> or a no-cors fetch looks like: no Origin, but the browser marks it cross-site.
        assertEquals(403, get("/health", "Sec-Fetch-Site" to "cross-site").statusCode())
    }

    @Test
    fun `a request addressed to another host name is refused, which is how DNS rebinding arrives`() {
        assertEquals(403, statusWithHost("evil.example:$port"))
    }

    @Test
    fun `the screenshot and syntax endpoints answer to the same rule`() {
        assertEquals(403, get("/syntax", "Origin" to "https://evil.example").statusCode())
        assertEquals(403, get("/screenshot", "Origin" to "https://evil.example").statusCode())
    }

    // ------------------------------------------------------------------ what still gets in

    @Test
    fun `a tool that is not a browser still gets in`() {
        assertEquals(200, get("/health").statusCode())
        assertEquals(200, post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").statusCode())
    }

    @Test
    fun `every loopback name the server can be reached by is accepted`() {
        assertEquals(200, statusWithHost("127.0.0.1:$port"))
        assertEquals(200, statusWithHost("localhost:$port"))
        assertEquals(200, statusWithHost("LOCALHOST:$port"))
        assertEquals(200, statusWithHost("[::1]:$port"))
        // A tunnel (`ssh -L 9000:127.0.0.1:8765`) arrives on another port, and is still this machine by name.
        assertEquals(200, statusWithHost("localhost:9000"))
    }

    @Test
    fun `a page served from this machine, and a URL typed into the address bar, still get in`() {
        assertEquals(200, get("/health", "Origin" to "http://localhost:3000").statusCode())
        assertEquals(200, get("/health", "Origin" to "http://127.0.0.1:$port").statusCode())
        assertEquals(200, get("/health", "Sec-Fetch-Site" to "none").statusCode())
        assertEquals(200, get("/health", "Sec-Fetch-Site" to "same-origin").statusCode())
    }
}
