package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L'annonceur suit le cap : un changement de cap fait reparler aussitôt. */
class GuidanceAnnouncerSteeringTest {

    @Test
    fun `changement de cap fait reparler aussitot`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Tout droit.", announcer.onFrame(0L, emptyList()))
        assertEquals(
            "Gauche.",
            announcer.onFrame(1_000L, emptyList(), SpeechLanguage.Fr, SteeringCap.Left),
        )
    }

    @Test
    fun `meme cap meme scene reste muet`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Droite.", announcer.onFrame(0L, emptyList(), SpeechLanguage.Fr, SteeringCap.Right))
        assertNull(announcer.onFrame(1_000L, emptyList(), SpeechLanguage.Fr, SteeringCap.Right))
    }
}
