package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L'annonceur suit le cap : un changement de cap fait reparler, comme un obstacle. */
class GuidanceAnnouncerSteeringTest {

    @Test
    fun `changement de cap fait reparler apres stabilite`() {
        val announcer = GuidanceAnnouncer()

        assertNull(announcer.onFrame(0L, emptyList()))
        assertEquals("Continue tout droit.", announcer.onFrame(1_000L, emptyList()))
        assertNull(announcer.onFrame(6_000L, emptyList(), SpeechLanguage.Fr, SteeringCap.Left))
        assertEquals(
            "Va à gauche.",
            announcer.onFrame(7_000L, emptyList(), SpeechLanguage.Fr, SteeringCap.Left),
        )
    }

    @Test
    fun `meme cap meme scene reste muet`() {
        val announcer = GuidanceAnnouncer()

        announcer.onFrame(0L, emptyList(), SpeechLanguage.Fr, SteeringCap.Right)
        announcer.onFrame(1_000L, emptyList(), SpeechLanguage.Fr, SteeringCap.Right)

        assertNull(announcer.onFrame(7_000L, emptyList(), SpeechLanguage.Fr, SteeringCap.Right))
    }
}
