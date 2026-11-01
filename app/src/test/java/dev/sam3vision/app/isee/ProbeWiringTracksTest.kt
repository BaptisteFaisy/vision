package dev.sam3vision.app.isee

import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.FreeSpace
import dev.sam3vision.core.live.PersonTracker
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Câblage du suivi de foule (audit du 01/11) : l'identité d'une personne tient
 * d'une frame à l'autre, et l'âge de la mesure traverse jusqu'à la décision.
 *
 * On teste ici ce que le câblage **donne** au guidage ([ProbeGuideFrame]) — pas
 * la décision elle-même, qui vit dans `:core`. Rien d'Android : horloge et
 * chronologie sont des paramètres.
 */
class ProbeWiringTracksTest {

    /** Personne à [x] du cadre, mesurée à [meters] : boîte de 10 % de large. */
    private fun personne(x: Float, meters: Float) =
        PlacedLabel("person", x, 0.5f, meters, x - 0.05f, x + 0.05f)

    /** Chronologie d'une frame : 100 + 100 + 400 + 100 = 700 ms de capture aux masques. */
    private val septCentsMs = FrameTimings(
        captureToJpegMs = 100, jpegToSentMs = 100, sentToReceivedMs = 400, receivedToOverlaysMs = 100,
    )

    @Test
    fun `deux frames de la meme personne gardent le meme trackId`() {
        val tracker = PersonTracker()
        val premiere = probeGuideFrameOf(tracker, 10_000L, septCentsMs, listOf(personne(0.50f, 3f)), 67f)
        val suivante = probeGuideFrameOf(tracker, 10_400L, septCentsMs, listOf(personne(0.52f, 2.4f)), 67f)

        val id = premiere.labels.single().trackId
        assertNotNull("la première frame ouvre une piste", id)
        assertEquals("même personne, même piste", id, suivante.labels.single().trackId)
        assertTrue("0,6 m en 0,4 s : ça se rapproche", (suivante.labels.single().closingMps ?: 0f) > 0f)
    }

    @Test
    fun `deux personnes dans la frame ont deux pistes`() {
        val frame = probeGuideFrameOf(
            PersonTracker(), 10_000L, septCentsMs,
            listOf(personne(0.2f, 2f), personne(0.8f, 3f)), 67f,
        )

        assertEquals(2, frame.labels.size)
        assertNotEquals(frame.labels[0].trackId, frame.labels[1].trackId)
        assertEquals("l'ordre reçu est gardé", listOf(0.2f, 0.8f), frame.labels.map { it.centerX })
    }

    @Test
    fun `un age de 700 ms est transmis a la decision`() {
        val frame = probeGuideFrameOf(
            PersonTracker(), 10_000L, septCentsMs, listOf(personne(0.5f, 2f)), 67f,
        )
        val capture = capturedAtMsOf(10_000L, septCentsMs)

        assertEquals(700L, septCentsMs.totalMs)
        assertEquals(700L, frame.ageMs)
        assertNotNull("instant de capture relu", capture)
        assertEquals("10 s − 700 ms", 9_300L, capture!!)
    }

    @Test
    fun `sans chronologie l age est nul, jamais invente`() {
        val muet = FrameTimings()

        assertNull(capturedAtMsOf(10_000L, muet))
        assertEquals(0L, frameAgeMsOf(10_000L, muet))
        assertEquals(0L, probeGuideFrameOf(PersonTracker(), 10_000L, muet, emptyList(), 67f).ageMs)
    }

    @Test
    fun `la vitesse de marche est celle du guidage, la place libre vient des bords vus`() {
        val bord = PlacedLabel("curb", 0.7f, 0.6f, 1f, 0.65f, 0.75f)
        val frame = probeGuideFrameOf(PersonTracker(), 10_000L, septCentsMs, listOf(bord), 67f)

        assertEquals(Timing.WALK_MPS, frame.speedMps, 0f)
        assertEquals(0f, frame.free.rightM, 0.001f)
        assertEquals(FreeSpace.DEFAULT_HALF_M, frame.free.leftM, 0.001f)
    }

    @Test
    fun `le reset de seance oublie les pistes`() {
        val avant = probeGuideFrameOf(ProbeTracks.tracker, 1_000L, septCentsMs, listOf(personne(0.5f, 2f)), 67f)
        ProbeTracks.reset()
        val apres = probeGuideFrameOf(ProbeTracks.tracker, 1_100L, septCentsMs, listOf(personne(0.5f, 2f)), 67f)

        assertNotEquals(
            "un id d'avant séance ne désigne pas quelqu'un d'après",
            avant.labels.single().trackId, apres.labels.single().trackId,
        )
    }
}
