package dev.sam3vision.core

import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Serveur HTTP minimal pour les tests JVM (MockWebServer n'est pas dans le cache Gradle).
 * Une requête = une connexion, réponse JSON synchrone avec Content-Length.
 */
class TestHttpServer(private val handler: (RecordedRequest) -> Response) : Closeable {

    data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    data class Response(
        val code: Int,
        val body: String,
        val delayMs: Long = 0,
        /** Corps binaire (audio TTS) ; prioritaire sur [body] quand non nul. */
        val bodyBytes: ByteArray? = null,
        val contentType: String = "application/json",
    )

    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "test-http-accept") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            thread(isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val request = runCatching { readRequest(it.getInputStream()) }.getOrNull() ?: return
            requests += request
            val response = runCatching { handler(request) }.getOrDefault(Response(500, "{}"))
            if (response.delayMs > 0) Thread.sleep(response.delayMs)
            runCatching { writeResponse(it, response) }
        }
    }

    private fun readRequest(input: InputStream): RecordedRequest {
        val requestLine = readLine(input) ?: return RecordedRequest("", "", emptyMap(), "")
        val parts = requestLine.split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) readExactly(input, length).toString(Charsets.UTF_8) else ""
        return RecordedRequest(
            method = parts.getOrElse(0) { "" },
            path = parts.getOrElse(1) { "" },
            headers = headers,
            body = body,
        )
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == length) buffer else buffer.copyOf(offset)
    }

    private fun writeResponse(socket: Socket, response: Response) {
        val body = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${response.code} ${statusText(response.code)}\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(body)
            flush()
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        402 -> "Payment Required"
        403 -> "Forbidden"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        else -> "Error"
    }

    override fun close() {
        runCatching { server.close() }
    }
}
