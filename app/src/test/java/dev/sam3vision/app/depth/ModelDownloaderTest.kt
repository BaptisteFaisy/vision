package dev.sam3vision.app.depth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

class ModelDownloaderTest {

    private fun shaOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Faux serveur HTTP minimaliste (une connexion, `ServerSocket` pur — pas
     * de `com.sun.*`, invisible au classpath des tests unitaires) : répond
     * [code] + [body] puis ferme.
     */
    private suspend fun withServer(body: ByteArray, code: String = "200 OK", block: suspend (String) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        for (line in generateSequence { reader.readLine() }) {
                            if (line.isEmpty()) break
                        }
                        val out = socket.getOutputStream()
                        val head = "HTTP/1.1 $code\r\nContent-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                        out.write(body)
                        out.flush()
                    }
                } catch (e: Exception) {
                    // Serveur de test : une connexion avortée n'est pas un échec.
                }
            }
            try {
                block("http://127.0.0.1:${server.localPort}/model.bin")
            } finally {
                worker.join(5_000)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `fichier present et empreinte ok - reutilise sans reseau`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("dl-ok").toFile()
        try {
            val bytes = ByteArray(300_000) { (it % 251).toByte() }
            val store = File(dir, DepthModelStore.DIR)
            store.mkdirs()
            File(store, "model.bin").writeBytes(bytes)
            val spec = DepthModelSpec("model.bin", "http://127.0.0.1:1/injoignable", shaOf(bytes), bytes.size.toLong())
            val result = ModelDownloader.ensureSpec(dir, spec)
            assertTrue(result.isSuccess)
            assertEquals(bytes.size.toLong(), result.getOrThrow().length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `telecharge puis verifie - contenu et progression`() = runBlocking {
        val bytes = ByteArray(300_000) { (it * 7 % 251).toByte() }
        withServer(bytes) { url ->
            val dir = kotlin.io.path.createTempDirectory("dl-get").toFile()
            try {
                val spec = DepthModelSpec("model.bin", url, shaOf(bytes), bytes.size.toLong())
                var calls = 0
                var lastDone = 0L
                val result = ModelDownloader.ensureSpec(dir, spec) { done, _ ->
                    calls++
                    lastDone = done
                }
                assertTrue(result.isSuccess)
                assertTrue(result.getOrThrow().readBytes().contentEquals(bytes))
                assertTrue("progression signalée", calls > 0)
                assertEquals(bytes.size.toLong(), lastDone)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `empreinte invalide - echec et tmp nettoye`() = runBlocking {
        val bytes = ByteArray(10_000) { 1 }
        withServer(bytes) { url ->
            val dir = kotlin.io.path.createTempDirectory("dl-bad").toFile()
            try {
                val spec = DepthModelSpec("model.bin", url, shaOf(ByteArray(10_000) { 2 }), bytes.size.toLong())
                val result = ModelDownloader.ensureSpec(dir, spec)
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()!!.message!!.contains("sha256"))
                assertFalse(File(File(dir, DepthModelStore.DIR), "model.bin").exists())
                assertFalse(File(File(dir, DepthModelStore.DIR), "model.bin.tmp").exists())
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `http 404 - echec explicite`() = runBlocking {
        withServer(ByteArray(0), "404 Not Found") { url ->
            val dir = kotlin.io.path.createTempDirectory("dl-404").toFile()
            try {
                val spec = DepthModelSpec("model.bin", url, shaOf(ByteArray(0)), 0)
                val result = ModelDownloader.ensureSpec(dir, spec)
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()!!.message!!.contains("404"))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `reseau coupe - message clair`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("dl-off").toFile()
        try {
            val spec = DepthModelSpec("model.bin", "http://127.0.0.1:1/injoignable", shaOf(ByteArray(0)), 0)
            val result = ModelDownloader.ensureSpec(dir, spec)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("network unavailable"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
