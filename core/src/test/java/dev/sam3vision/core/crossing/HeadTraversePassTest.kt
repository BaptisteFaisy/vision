package dev.sam3vision.core.crossing

import dev.sam3vision.core.haptics.HapticPattern
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passe traversée de bout en bout sur pixels réels (31/10) : les boîtes
 * tête — concept anglais et alias français du serveur — sont préférées au
 * caisson, la couleur s'y lit (Panoramax `feuxfr/`, rouge puis vert), et
 * chaque verdict sort en voix ([CrossingAnnouncer]) et en vibration
 * ([HapticPattern], l'objet que `IseeHaptics.crossing` joue). Un seul passage
 * ininterrompu, sans GPU : la segmentation SAM est remplacée par ses
 * boîtes (étiquette + score + boîte), tout l'aval est le vrai code.
 */
class HeadTraversePassTest {

    @Test
    fun `tete rouge puis tete verte boitier et petit feu jusqu a voix et vibration`() {
        // 1. Routage : chaque forme de tête bat le caisson au meilleur score.
        val housing = SegObject("pedestrian traffic light", 0.95f, listOf(0f, 0f, 200f, 400f), "")
        val heads = listOf(
            SegObject("pedestrian signal head", 0.60f, listOf(80f, 120f, 120f, 200f), ""),
            SegObject("boîtier feu piéton", 0.61f, listOf(80f, 120f, 120f, 200f), ""),
            SegObject("petit feu piéton", 0.62f, listOf(80f, 120f, 120f, 200f), ""),
        )
        heads.forEach { head ->
            assertTrue(head.label, CrossingPrompts.isHeadLabel(head.label))
            assertEquals(
                listOf(0.4f, 0.3f, 0.6f, 0.5f),
                CrossingPrompts.selectLightBox(listOf(housing, head), 200, 400),
            )
        }
        // 2. Couleur : verdicts entraîné + porte sur crops réels (rouge, vert).
        val (redPx, redW, redH) = ppmPixels(resource("feuxfr/fr_red_fig_548.ppm"))
        assertEquals(CrossingState.Red, PedLightGate.verdict(redPx, redW, redH))
        val (greenPx, greenW, greenH) = ppmPixels(resource("feuxfr/fr_green_fig_21.ppm"))
        assertEquals(CrossingState.Green, PedLightGate.verdict(greenPx, greenW, greenH))
        // 3. Voix : un ordre par état confirmé (phases espacées du cooldown).
        val announcer = CrossingAnnouncer()
        val redCues = (0 until 10).mapNotNull { i ->
            announcer.onFrame(i * 100L, CrossingState.Red, SpeechLanguage.Fr)
        }
        assertTrue(redCues.any { it.state == CrossingState.Red && it.phrase.isNotBlank() })
        val greenCues = (0 until 10).mapNotNull { i ->
            announcer.onFrame(10_000L + i * 100L, CrossingState.Green, SpeechLanguage.Fr)
        }
        assertTrue(greenCues.any { it.state == CrossingState.Green && it.phrase.isNotBlank() })
        // 4. Haptique : double salve pour le vert, créneau long pour le rouge.
        val greenWave = HapticPattern.crossingGreen()
        val redWave = HapticPattern.crossingRed()
        assertEquals(4, greenWave.timings.size)
        assertEquals(2, redWave.timings.size)
    }

    /** Octets d'une ressource de test (`feuxfr/`, PPM P6). */
    private fun resource(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name)?.readBytes()
            ?: error("ressource manquante : $name")

    /** Pixels ARGB d'un PPM P6 brut (pas d'ImageIO sur le classpath des tests). */
    private fun ppmPixels(bytes: ByteArray): Triple<IntArray, Int, Int> {
        var pos = 0
        fun token(): String {
            while (pos < bytes.size && bytes[pos] in SPACES) pos++
            val start = pos
            while (pos < bytes.size && bytes[pos] !in SPACES) pos++
            return String(bytes, start, pos - start, Charsets.US_ASCII)
        }
        require(token() == "P6") { "PPM attendu" }
        val width = token().toInt()
        val height = token().toInt()
        require(token() == "255") { "PPM 255 attendu" }
        pos++
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = bytes[pos++].toInt() and 0xFF
            val g = bytes[pos++].toInt() and 0xFF
            val b = bytes[pos++].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Triple(pixels, width, height)
    }

    companion object {
        private val SPACES = setOf(' '.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\t'.code.toByte())
    }
}
