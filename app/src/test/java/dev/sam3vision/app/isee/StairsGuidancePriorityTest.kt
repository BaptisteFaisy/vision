package dev.sam3vision.app.isee

import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairDirection
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairWatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés. */
private class StairsPriorityFakeSpeaker : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text)
    }

    override fun cut() = Unit
}

/**
 * Escaliers devant « droite » / « gauche » (ordre user du 02/11/2026) : les
 * escaliers (niveau 6) coupent un mot de guidage en cours (manœuvre 11,
 * rappel 3, comme la bordure) et ne sont jamais coupés par eux (ni par le
 * repli 12). Les paliers 2 / 1 / 0,5 m sont couverts côté cœur
 * ([StairWatcher.COUNTDOWN_METERS], `StairAnnounceTest`).
 */
class StairsGuidancePriorityTest {

    private val speaker = StairsPriorityFakeSpeaker()

    @Before
    fun brancher() {
        ProbeBackgroundMotion.reset()
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
        ProbeBackgroundMotion.reset()
    }

    @Test
    fun `escaliers coupent la manoeuvre en cours`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.STAIRS, "Stairs 2 m.")

        assertEquals(
            listOf(
                StairsPriorityFakeSpeaker.Said(OutdoorLevel.GPS_EVENT, "Left."),
                StairsPriorityFakeSpeaker.Said(OutdoorLevel.STAIRS, "Stairs 2 m."),
            ),
            speaker.said,
        )
    }

    @Test
    fun `escaliers coupent le rappel en cours`() {
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Right.")
        VoiceBus.post(OutdoorLevel.STAIRS, "Stairs 1 m.")

        assertEquals(2, speaker.said.size)
        assertEquals(OutdoorLevel.STAIRS, speaker.said[1].level)
    }

    @Test
    fun `rappel attend la fin des escaliers`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "Stairs 2 m.")
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `manoeuvre et repli n interrompent pas les escaliers`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "Stairs 0.5 m.")
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Right.")
        VoiceBus.post(OutdoorLevel.FALLBACK, "Left.")

        assertEquals(
            listOf(StairsPriorityFakeSpeaker.Said(OutdoorLevel.STAIRS, "Stairs 0.5 m.")),
            speaker.said,
        )
    }

    @Test
    fun `jalon 2m coupe le mot via le chemin sonde`() {
        // Jalon réel du guetteur (confirmFrames = 2, miroir `ProbeStairs`),
        // proposé au bus comme la sonde (antirépétition puis post).
        val watcher = StairWatcher(confirmFrames = 2)
        val signal = StairSignal(detected = true, confidence = 1f, lineCount = 5, roiTop = 0, roiBottom = 0)
        watcher.onSignal(signal, 0L, SpeechLanguage.En, StairDirection.Up, null, 2.5f)
        watcher.onSignal(signal, 1000L, SpeechLanguage.En, StairDirection.Up, null, 1.9f)
        val cue = watcher.onSignal(signal, 2000L, SpeechLanguage.En, StairDirection.Up, null, 1.8f)!!

        assertEquals("Stairs 2 m.", cue.phrase)

        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        val offered = offer(VoiceWish(cue.level, cue.phrase, cue.antiRepeatKey), 3000L)

        assertEquals(true, offered)
        assertEquals(2, speaker.said.size)
        assertEquals(OutdoorLevel.STAIRS, speaker.said[1].level)
    }
}
