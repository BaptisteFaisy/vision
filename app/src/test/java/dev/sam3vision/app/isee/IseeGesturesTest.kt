package dev.sam3vision.app.isee

import dev.sam3vision.app.isee.vm.Dictated
import dev.sam3vision.app.isee.vm.FindListening
import dev.sam3vision.app.isee.vm.IseeSwipeOrders
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le relais des gestes : chacun part à son exécutant — tenue et tap à la
 * voix et à la vidéo, haut à la Traversée, bas au GPS, droite à la création
 * d'un picto (comme le bouton). La gauche (lunettes) vit dans `MainActivity`,
 * seule à voir les lunettes.
 */
class IseeGesturesTest {

    private val calls = mutableListOf<String>()
    private val gpsOrders = mutableListOf<String>()
    private var state = IseeUiState()

    /** Micro factice et muet : le routage GPS n'en a pas besoin. */
    private object MuteListening : FindListening {
        override fun start(): Boolean = false
        override suspend fun finish(settings: Settings): Dictated? = null
        override fun discard() = Unit
    }

    private fun TestScope.gestures() = IseeGestures(
        holdStart = { calls += "hold-start" },
        holdEnd = { calls += "hold-end" },
        holdCancel = { calls += "hold-cancel" },
        singleTap = { calls += "tap" },
        swipe = IseeSwipeOrders(
            scope = this,
            runMode = { _: String -> true },
            runGpsOrder = { gpsOrders += it; true },
            dictation = MuteListening,
            voiceDetected = { false },
            settings = { Settings.Default },
            placeMode = { PictoPlaceMode.DEDANS },
            sayClip = { _: String -> },
            update = { fn -> state = fn(state) },
            runDestination = { _: String -> true },
            silence = {},
            hapticStart = {},
            hapticEnd = {},
            isActive = { true },
        ),
        toggleCrossing = { calls += "crossing" },
        createPicto = { calls += "picto" },
    )

    @Test
    fun `la droite cree un picto, comme le bouton`() = runTest {
        gestures().onSwipeRight()

        assertEquals(listOf("picto"), calls)
    }

    @Test
    fun `le haut bascule la Traversee`() = runTest {
        gestures().onSwipeUp()

        assertEquals(listOf("crossing"), calls)
    }

    @Test
    fun `le bas rejoue l'ordre GPS`() = runTest {
        gestures().onSwipeDown()
        advanceUntilIdle()

        assertEquals(listOf(IseeSwipeOrders.GPS_ENABLE_ORDER), gpsOrders)
        assertEquals("rien d'autre", emptyList<String>(), calls)
    }

    @Test
    fun `tenue et tap vont a la voix et a la video`() = runTest {
        val routed = gestures()
        routed.onHoldStart()
        routed.onHoldEnd()
        routed.onHoldCancel()
        routed.onSingleTap()

        assertEquals(listOf("hold-start", "hold-end", "hold-cancel", "tap"), calls)
    }
}
