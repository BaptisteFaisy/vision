package dev.sam3vision.app

import dev.sam3vision.app.live.PredictionLayer
import dev.sam3vision.app.live.TrailLayer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.picto.PictoHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scopage pixels/chiffres du Direct, verrouillé clé par clé : la clé pixels
 * ne bouge que sur l'étage caméra, la clé chiffres que sur les bandeaux.
 * (`overlays` ne varie pas sur JVM — pas de `Bitmap` — mais sa sémantique
 * d'identité est celle de `ProbeSlices` : paquet neuf = clé neuve.)
 */
class LiveSlicesTest {

    private val frame = LiveFrame(width = 320, height = 320, elapsedMs = 100L, objects = emptyList())

    @Test
    fun `pixels - l'etage camera seul la fait bouger`() {
        val base = LiveUiState()
        val changed = listOf(
            base.copy(running = true),
            base.copy(error = "boom"),
            base.copy(frame = frame),
            base.copy(prompts = listOf("person")),
            base.copy(distanceLayer = DistanceLayer(width = 1, height = 1)),
            base.copy(traverseActive = true),
            base.copy(crossing = CrossingState.Red),
            base.copy(crossingBox = listOf(0f, 0f, 1f, 1f)),
            base.copy(pictoOverlay = PictoOverlay(width = 1, height = 1, hits = listOf(hit()))),
            base.copy(prediction = PredictionLayer(width = 1, height = 1)),
            base.copy(trails = TrailLayer(width = 1, height = 1)),
        )
        changed.forEachIndexed { i, s ->
            assertTrue("étage caméra #$i", livePixelKeyOf(s) != livePixelKeyOf(base))
        }
        val untouched = listOf(
            base.copy(fps = 2f),
            base.copy(latencyMs = 100L),
            base.copy(spoken = "chaise"),
            base.copy(voice = LiveVoiceState(text = "quoi ?")),
            base.copy(memory = LiveMemoryState(question = "?")),
            base.copy(narration = "une rue"),
            base.copy(transport = "WebSocket"),
            base.copy(localActive = true),
            base.copy(timings = FrameTimings(captureToJpegMs = 3)),
        )
        untouched.forEachIndexed { i, s ->
            assertEquals("chiffres seuls #$i", livePixelKeyOf(base), livePixelKeyOf(s))
        }
    }

    @Test
    fun `chiffres - les bandeaux seuls les font bouger`() {
        val base = LiveUiState()
        val changed = listOf(
            base.copy(fps = 2f),
            base.copy(latencyMs = 100L),
            base.copy(spoken = "chaise"),
            base.copy(voice = LiveVoiceState(text = "quoi ?")),
            base.copy(memory = LiveMemoryState(question = "?")),
            base.copy(narration = "une rue"),
            base.copy(narrationBusy = true),
            base.copy(transport = "WebSocket"),
            base.copy(localActive = true),
            base.copy(stairsLine = "escalier"),
            base.copy(frame = frame),
        )
        changed.forEachIndexed { i, s ->
            assertTrue("bandeaux #$i", liveRestKeyOf(s) != liveRestKeyOf(base))
        }
        val untouched = listOf(
            base.copy(prediction = PredictionLayer(width = 1, height = 1)),
            base.copy(trails = TrailLayer(width = 1, height = 1)),
            base.copy(pictoOverlay = PictoOverlay(width = 1, height = 1, hits = listOf(hit()))),
            base.copy(crossing = CrossingState.Red),
            base.copy(crossingBox = listOf(0f, 0f, 1f, 1f)),
            base.copy(timings = FrameTimings(captureToJpegMs = 3)),
        )
        untouched.forEachIndexed { i, s ->
            assertEquals("pixels seuls #$i", liveRestKeyOf(base), liveRestKeyOf(s))
        }
    }

    private fun hit() = PictoHit(
        pictoId = "cle",
        label = "clé",
        x = 0f, y = 0f, w = 0.1f, h = 0.1f,
        score = 0.9f,
    )
}
