package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.SegObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vols qui se recouvrent : `runFrame` lance la frame suivante avant de
 * publier (débit GPU), donc deux publications partagent les instances de
 * la sonde (`FrameLabels`, trackers, veto, chronologie) — ça tuait l'app
 * (`ArrayIndexOutOfBounds` dans `FrameLabels.of`, émulateur le 01/11).
 * La section publication est sérialisée, le réseau reste parallèle.
 */
class LiveProbePublishConcurrencyTest {

    /** Objet sans masque (décodage Bitmap impossible en JVM, inutile ici). */
    private fun objet(label: String, x0: Float) = SegObject(
        label = label,
        score = 0.9f,
        box = listOf(x0, 400f, x0 + 100f, 900f),
        maskPngB64 = "",
        maskEncoding = MASK_ENCODING_NONE,
    )

    private fun frame() = LiveFrame(
        width = 512,
        height = 1138,
        elapsedMs = 120L,
        objects = listOf(
            objet("person", 206f),
            objet("car", 10f),
            objet("bus", 320f),
            objet("traffic light", 420f),
        ),
    )

    @Test
    fun `deux vols qui publient ensemble ne plantent pas`() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val handler = CoroutineExceptionHandler { _, e ->
            if (e is CancellationException) return@CoroutineExceptionHandler
            errors.add(e)
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + handler)
        try {
            val fake = ProbeTestClient(mutableListOf())
            val probe = LiveProbe(
                scope = scope,
                clock = { 0L },
                background = Dispatchers.Default,
                clientFor = { _, _, _ -> fake },
            )
            probe.configure(PROBE_TEST_ENDPOINT)
            probe.begin()
            // Scheduler coupé : ce test verrouille le recouvrement, pas les keyframes.
            probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
            runBlocking {
                withTimeout(120_000) {
                    var paires = 0
                    repeat(60) { cycle ->
                        val baseFrames = probe.state.value.frames
                        val baseSent = fake.sent
                        probe.onFrame("A$cycle")
                        probe.onFrame("B$cycle")
                        if (!attendre { fake.sent >= baseSent + 2 }) return@repeat
                        fake.respond("A$cycle", frame())
                        fake.respond("B$cycle", frame())
                        if (!attendre { probe.state.value.frames >= baseFrames + 2 }) return@repeat
                        paires += 1
                    }
                    assertTrue("pas assez de paires publiées : $paires", paires >= 20)
                }
            }
            assertTrue("plantage concurrent : ${errors.peek()}", errors.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    /** Attend [cond] (vrai = tenue, faux = cycle sauté, jamais bloquant). */
    private suspend fun attendre(cond: () -> Boolean): Boolean {
        repeat(100) {
            if (cond()) return true
            delay(20)
        }
        return cond()
    }
}
