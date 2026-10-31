package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Journalisation des crops (collecte réentraînement) : ligne JSONL stable,
 * version assainie, plafond par fichier puis silence. Le PNG Bitmap et
 * l'écriture réelle restent sur appareil (wrappers fins, comme
 * `CrossingPixels`) : ici, faux injectés.
 */
class LightCropLoggerTest {

    private val box = listOf(0.1f, 0.2f, 0.3f, 0.4f)
    private val crop = CrossingFrame(48, 64, IntArray(48 * 64))

    private fun logger(
        lines: MutableList<String>,
        maxPerFile: Int = 2000,
        version: String = "1.2.3",
    ): LightCropLogger =
        LightCropLogger(
            dir = File("/tmp/unused"),
            appVersion = version,
            maxPerFile = maxPerFile,
            writer = { _, line -> lines.add(line) },
            pngOf = { _, _, _ -> "UE5H" },
            log = {},
        )

    @Test
    fun `ligne jsonl - champs stables et version`() {
        val lines = mutableListOf<String>()
        val kept = logger(lines).offer(box, crop, CrossingState.Red, 1_700_000_000_000L)

        assertTrue(kept)
        assertEquals(1, lines.size)
        assertEquals(
            "{\"ts\":1700000000000,\"box\":[0.1,0.2,0.3,0.4],\"verdict\":\"Red\"," +
                "\"w\":48,\"h\":64,\"png\":\"UE5H\",\"v\":\"1.2.3\"}",
            lines[0],
        )
    }

    @Test
    fun `version assainie - ni guillemets ni controles`() {
        val lines = mutableListOf<String>()
        logger(lines, version = "1.0\";\nDROP").offer(box, crop, CrossingState.Green, 0L)

        assertTrue(lines[0].endsWith("\"v\":\"1.0___DROP\"}"))
    }

    @Test
    fun `plafond - silence au-dela, sans ecrire`() {
        val lines = mutableListOf<String>()
        val log = logger(lines, maxPerFile = 2)

        assertTrue(log.offer(box, crop, CrossingState.Red, 1L))
        assertTrue(log.offer(box, crop, CrossingState.Green, 2L))
        assertFalse(log.offer(box, crop, CrossingState.Red, 3L))
        assertFalse(log.offer(box, crop, CrossingState.Red, 4L))
        assertEquals(2, lines.size)
    }

    @Test
    fun `nom de fichier - prefixe et extension jsonl`() {
        assertEquals("lightcrops-abc.jsonl", lightCropFileName("abc"))
    }
}
