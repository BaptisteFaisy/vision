package dev.sam3vision.app.isee

import dev.sam3vision.core.live.AntiRepeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés (niveaux + textes). */
private class TickFakeSpeaker : VoiceBusSpeaker {
    val said = mutableListOf<Pair<Int, String>>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += level to text
    }

    override fun cut() = Unit
}

/**
 * Battement vocal 350 ms : les producteurs continus rapportent leur vœu
 * courant, le battement propose le plus prioritaire au bus — une fois par
 * fenêtre d'antirépétition, jamais deux fois le même dans la seconde, et
 * silence en Traversée (le verdict y est propriétaire du son).
 */
class VoiceTickTest {

    private val speaker = TickFakeSpeaker()

    @Before
    fun brancher() {
        VoiceTick.reset()
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
        VoiceTick.reset()
    }

    @Test
    fun `battement 350 ms`() {
        assertEquals(350L, VoiceTick.TICK_MS)
    }

    @Test
    fun `pick prend le plus petit niveau`() {
        VoiceTick.report(
            VoiceTick.SRC_SIDEWALK,
            VoiceWish(OutdoorLevel.SIDEWALK, "trottoir", AntiRepeat.KEY_SIDEWALK),
            1_000L,
        )
        VoiceTick.report(
            VoiceTick.SRC_FENCE,
            VoiceWish(OutdoorLevel.FENCE, "grillage", AntiRepeat.KEY_FENCE),
            1_000L,
        )
        VoiceTick.report(
            VoiceTick.SRC_STAIRS,
            VoiceWish(OutdoorLevel.STAIRS, "escalier", AntiRepeat.KEY_STAIRS),
            1_000L,
        )

        assertEquals("grillage", VoiceTick.pick(1_100L)?.text)
    }

    @Test
    fun `pick ex aequo prend le plus recent`() {
        VoiceTick.report(VoiceTick.SRC_FENCE, VoiceWish(4, "vieux", "test-tick-vieux"), 1_000L)
        VoiceTick.report(VoiceTick.SRC_STAIRS, VoiceWish(4, "frais", "test-tick-frais"), 1_100L)

        assertEquals("frais", VoiceTick.pick(1_200L)?.text)
    }

    @Test
    fun `pick ignore perime blanc et source effacee`() {
        VoiceTick.report(VoiceTick.SRC_FENCE, VoiceWish(4, "grillage", AntiRepeat.KEY_FENCE), 1_000L)
        assertEquals("grillage", VoiceTick.pick(1_000L + VoiceTick.FRESH_MS - 1)?.text)
        assertNull(VoiceTick.pick(1_000L + VoiceTick.FRESH_MS))

        VoiceTick.report(VoiceTick.SRC_FENCE, VoiceWish(4, "   ", AntiRepeat.KEY_FENCE), 2_000L)
        assertNull(VoiceTick.pick(2_000L))

        VoiceTick.report(VoiceTick.SRC_FENCE, VoiceWish(4, "grillage", AntiRepeat.KEY_FENCE), 3_000L)
        VoiceTick.report(VoiceTick.SRC_FENCE, null, 3_100L)
        assertNull(VoiceTick.pick(3_100L))
    }

    @Test
    fun `tick propose le gagnant au bus`() {
        VoiceTick.report(
            VoiceTick.SRC_SIDEWALK,
            VoiceWish(OutdoorLevel.SIDEWALK, "Trottoir à gauche.", AntiRepeat.KEY_SIDEWALK),
            0L,
        )
        VoiceTick.report(
            VoiceTick.SRC_FENCE,
            VoiceWish(OutdoorLevel.FENCE, "Grillage fermé devant vous.", AntiRepeat.KEY_FENCE),
            0L,
        )

        assertTrue(VoiceTick.tick(0L))

        assertEquals(listOf(OutdoorLevel.FENCE to "Grillage fermé devant vous."), speaker.said)
    }

    @Test
    fun `tick ne re-propose pas l identique avant une seconde`() {
        val calls = mutableListOf<Long>()
        VoiceTick.report(VoiceTick.SRC_FENCE, VoiceWish(4, "grillage", "test-voicetick-dedup"), 0L)

        assertTrue(VoiceTick.tick(0L) { _, at -> calls += at; true })
        assertFalse(VoiceTick.tick(350L) { _, at -> calls += at; true })
        assertTrue(VoiceTick.tick(1_000L) { _, at -> calls += at; true })

        assertEquals(listOf(0L, 1_000L), calls)
    }

    @Test
    fun `tick refuse par l antirepetition retente au battement suivant`() {
        VoiceTick.report(
            VoiceTick.SRC_FENCE,
            VoiceWish(OutdoorLevel.FENCE, "Grillage fermé devant vous.", AntiRepeat.KEY_FENCE),
            0L,
        )
        assertTrue(VoiceTick.tick(0L))
        assertEquals(1, speaker.said.size)

        // Fenêtre grillage 4 s : le battement retente mais l'antirépétition tait.
        VoiceTick.report(
            VoiceTick.SRC_FENCE,
            VoiceWish(OutdoorLevel.FENCE, "Grillage fermé devant vous.", AntiRepeat.KEY_FENCE),
            1_500L,
        )
        assertFalse(VoiceTick.tick(1_500L))
        assertEquals(1, speaker.said.size)

        // Fenêtre écoulée : le vœu toujours frais reparle.
        VoiceTick.report(
            VoiceTick.SRC_FENCE,
            VoiceWish(OutdoorLevel.FENCE, "Grillage fermé devant vous.", AntiRepeat.KEY_FENCE),
            4_100L,
        )
        assertTrue(VoiceTick.tick(4_100L))
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `boucle propose toutes les 350 ms, tait en traversee`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Long>()
            var crossing = false
            fun say(src: String, level: Int, text: String) =
                VoiceTick.report(src, VoiceWish(level, text, "test-voicetick-boucle"), testScheduler.currentTime)
            say(VoiceTick.SRC_SIDEWALK, OutdoorLevel.SIDEWALK, "Trottoir à gauche.")
            voiceTickJob(
                scope,
                { crossing },
                nowMs = { testScheduler.currentTime },
                offer = { _, at -> calls += at; true },
            )

            advanceTimeBy(350)
            runCurrent()
            assertEquals(listOf(350L), calls)

            // Battement suivant : même vœu dans la seconde → l'offre n'est pas rappelée.
            advanceTimeBy(350)
            runCurrent()
            assertEquals(1, calls.size)

            // Vœu plus prioritaire : parlé au battement suivant.
            say(VoiceTick.SRC_FENCE, OutdoorLevel.FENCE, "Passage au milieu.")
            advanceTimeBy(350)
            runCurrent()
            assertEquals(2, calls.size)

            // Traversée : silence, même un vœu nouveau.
            crossing = true
            say(VoiceTick.SRC_STAIRS, OutdoorLevel.STAIRS, "Escalier devant.")
            advanceTimeBy(1_050)
            runCurrent()
            assertEquals(2, calls.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pick dedans ignore la chaine dehors et reciproquement`() {
        VoiceTick.report(
            VoiceTick.SRC_FENCE,
            VoiceWish(OutdoorLevel.FENCE, "Grillage fermé devant vous.", AntiRepeat.KEY_FENCE),
            1_000L,
        )
        VoiceTick.report(
            VoiceTick.SRC_DOOR_WALL,
            VoiceWish(
                dev.sam3vision.core.picto.FindIndoorLevels.DOOR_WALL,
                "Door at 3 o'clock.",
                AntiRepeat.KEY_DOOR_WALL + ":DOOR:3",
            ),
            1_000L,
            indoor = true,
        )

        assertEquals("Grillage fermé devant vous.", VoiceTick.pick(1_100L, indoor = false)?.text)
        assertEquals("Door at 3 o'clock.", VoiceTick.pick(1_100L, indoor = true)?.text)
    }

    @Test
    fun `tick dedans propose le trouve cran zero`() {
        VoiceTick.report(
            VoiceTick.SRC_FIND,
            VoiceWish(
                dev.sam3vision.core.picto.FindIndoorLevels.FIND,
                "Keys at 9 o'clock.",
                AntiRepeat.KEY_FIND_FOUND + ":find_found_keys_9",
            ),
            0L,
            indoor = true,
        )

        assertTrue(VoiceTick.tick(0L, indoor = true))

        assertEquals(
            listOf(dev.sam3vision.core.picto.FindIndoorLevels.FIND to "Keys at 9 o'clock."),
            speaker.said,
        )
    }
}
