package dev.sam3vision.core.audio

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TempWavStoreTest {

    private val dir = Files.createTempDirectory("sam-wav").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `le WAV ecrit est complet et lisible`() {
        val store = TempWavStore(dir)
        val pcm = ByteArray(3200) { 7 }

        val file = store.write(pcm).getOrThrow()

        assertTrue(file.name.startsWith("dictee-"))
        assertTrue(file.name.endsWith(".wav"))
        assertEquals(44 + pcm.size, file.length().toInt())
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `seuls les derniers WAV sont gardes`() {
        val store = TempWavStore(dir, keep = 3)

        repeat(6) { store.write(ByteArray(160)).getOrThrow() }

        assertEquals(3, store.recent().size)
        assertEquals(3, dir.listFiles()?.size)
    }

    @Test
    fun `le dernier fichier ecrit est retrouvable`() {
        val store = TempWavStore(dir)

        val first = store.write(ByteArray(160)).getOrThrow()
        val last = store.write(ByteArray(320)).getOrThrow()

        assertEquals(last.name, store.latest()?.name)
        assertTrue(first.name != last.name)
    }

    @Test
    fun `le prefixe distingue les dictées du téléphone et des lunettes`() {
        val store = TempWavStore(dir, prefix = "lunettes")

        val file = store.write(ByteArray(320)).getOrThrow()

        assertTrue(file.name.startsWith("lunettes-"))
        assertEquals(file.name, store.latest()?.name)
    }

    @Test
    fun `un dossier impossible rend un echec sans exception`() {
        val blocker = dir.resolve("fichier")
        blocker.writeText("pas un dossier")
        val store = TempWavStore(blocker.resolve("sous"))

        assertTrue(store.write(ByteArray(160)).isFailure)
    }
}