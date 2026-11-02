package dev.sam3vision.app

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FIX 2 — `SpeechPlayer.stop()` idempotent, sans double-callback : `stop()` ne
 * déclenche jamais de rappel de fin, un rappel tardif d'une lecture tuée est
 * ignoré (garde de génération, modèle BusSpeaker), et la fin naturelle ne
 * rappelle qu'une fois. `MediaPlayer` étant indisponible en JVM, les lectures
 * sont armées par [SpeechPlayer.armPlaybackForTest] et terminées par
 * [SpeechPlayer.finishIfCurrent], exactement comme le ferait la plateforme.
 */
class Fix2StopIdempotentTest {

    private fun player(onComplete: () -> Unit = {}) =
        SpeechPlayer(Files.createTempDirectory("fix2").toFile(), onComplete = onComplete)

    @Test
    fun `stop sans lecture ne rappelle rien et ne plante pas`() {
        val p = player { error("aucun rappel attendu") }
        p.stop()
        p.stop()
    }

    @Test
    fun `lecture puis stop ignore le rappel tardif`() {
        var calls = 0
        val p = player()
        val gen = p.armPlaybackForTest()
        p.stop()
        p.finishIfCurrent(gen) { calls++ }
        assertEquals(0, calls)
    }

    @Test
    fun `fin naturelle rappelle onComplete une seule fois`() {
        var calls = 0
        val p = player()
        val gen = p.armPlaybackForTest()
        p.finishIfCurrent(gen) { calls++ }
        p.finishIfCurrent(gen) { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `double stop sans effet ni crash`() {
        var calls = 0
        val p = player()
        val gen = p.armPlaybackForTest()
        p.stop()
        p.stop()
        p.finishIfCurrent(gen) { calls++ }
        assertEquals(0, calls)
    }

    @Test
    fun `nouvelle lecture apres stop rappelle normalement`() {
        var calls = 0
        val p = player()
        p.armPlaybackForTest()
        p.stop()
        val gen = p.armPlaybackForTest()
        p.finishIfCurrent(gen) { calls++ }
        assertEquals(1, calls)
    }
}
