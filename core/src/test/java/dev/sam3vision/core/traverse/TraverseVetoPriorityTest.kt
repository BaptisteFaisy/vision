package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.model.Detection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le veto prime sur le vert : compléments à [TraverseVetoTest].
 *
 * Verrouille le mot d'ordre exact (« attendez », jamais « prêt » ni
 * « traversez » sous veto), la projection veto→rouge vue par l'annonceur,
 * la chaîne veto→phrase « Don't cross yet » et les littéraux FR préchauffés.
 *
 * Logique de [TraverseVeto] lue telle quelle (SCORE_MIN 0,35 strict, approche
 * confirmée, ALERTE puis reconfirmation) : ce fichier la vérifie, il ne la change pas.
 */
class TraverseVetoPriorityTest {

    private val focalPx = 1000f
    private val mp3 = ByteArray(64) { 0xFF.toByte() }
    private val nearBox = listOf(0f, 0f, 200f, 200f)
    private val smallBox = listOf(0f, 0f, 60f, 50f)

    private fun detection(label: String, score: Float, box: List<Float>): Detection =
        Detection(label, score, box, width = 640, height = 480)

    @Test
    fun `veto plus vert donne attendez - mot d ordre exact`() {
        val veto = TraverseVeto()
        veto.update(false, listOf(detection("car", 0.9f, smallBox)), focalPx)
        val decision = veto.update(true, listOf(detection("car", 0.9f, nearBox)), focalPx)
        assertTrue(decision.veto)
        assertEquals(TraversePhase.ATTENDRE, decision.state)
        assertEquals("wait", decision.cue)
        assertEquals("car", decision.blockingLabel)
        assertEquals(7.25f, decision.blockingMeters!!, 0.01f)
    }

    @Test
    fun `veto prime sur vert prepare - PRET puis ALERTE`() {
        val veto = TraverseVeto()
        assertEquals(TraversePhase.PRET, veto.update(true, listOf(detection("car", 0.9f, smallBox)), focalPx).state)
        val alert = veto.update(true, listOf(detection("car", 0.9f, nearBox)), focalPx)
        assertEquals(TraversePhase.ALERTE, alert.state)
        assertEquals("wait", alert.cue)
        assertTrue(alert.veto)
    }

    @Test
    fun `veto prime sur traversee - ALERTE puis reconfirmation PRET`() {
        val veto = TraverseVeto()
        veto.update(true, emptyList(), focalPx)
        veto.update(true, emptyList(), focalPx)
        assertEquals(TraversePhase.TRAVERSER, veto.currentState)
        veto.update(true, listOf(detection("car", 0.8f, smallBox)), focalPx)
        val alert = veto.update(true, listOf(detection("car", 0.8f, nearBox)), focalPx)
        assertEquals(TraversePhase.ALERTE, alert.state)
        assertEquals("wait", alert.cue)
        assertEquals(TraversePhase.PRET, veto.update(true, emptyList(), focalPx).state)
        assertEquals(TraversePhase.TRAVERSER, veto.update(true, emptyList(), focalPx).state)
    }

    @Test
    fun `sans veto le vert fait traverser - temoin`() {
        val veto = TraverseVeto()
        val first = veto.update(lightGreen = true, detections = emptyList(), focalPx = focalPx)
        assertEquals(TraversePhase.PRET, first.state)
        assertEquals("ready", first.cue)
        assertFalse(first.veto)
        val second = veto.update(lightGreen = true, detections = emptyList(), focalPx = focalPx)
        assertEquals(TraversePhase.TRAVERSER, second.state)
        assertEquals("cross", second.cue)
    }

    @Test
    fun `feu rouge plus veto reste attendre - pas d alerte depuis l attente`() {
        val veto = TraverseVeto()
        veto.update(false, listOf(detection("car", 0.9f, smallBox)), focalPx)
        val decision = veto.update(false, listOf(detection("car", 0.9f, nearBox)), focalPx)
        assertTrue(decision.veto)
        assertEquals(TraversePhase.ATTENDRE, decision.state)
        assertEquals("wait", decision.cue)
    }

    @Test
    fun `score limite - 0_35 ignore, 0_36 bloque`() {
        val weak = TraverseVeto()
        weak.update(true, listOf(detection("car", 0.35f, smallBox)), focalPx)
        val ignored = weak.update(true, listOf(detection("car", 0.35f, nearBox)), focalPx)
        assertFalse(ignored.veto)
        assertEquals(TraversePhase.TRAVERSER, ignored.state)
        val strong = TraverseVeto()
        strong.update(true, listOf(detection("car", 0.36f, smallBox)), focalPx)
        val blocked = strong.update(true, listOf(detection("car", 0.36f, nearBox)), focalPx)
        assertTrue(blocked.veto)
        assertEquals(TraversePhase.ALERTE, blocked.state)
        assertEquals("wait", blocked.cue)
    }

    @Test
    fun `phases vues par l annonceur - veto vaut rouge`() {
        fun decisionOf(phase: TraversePhase) = TraverseDecision(
            state = phase,
            cue = "x",
            veto = false,
            blockingLabel = null,
            blockingMeters = null,
            keepObstacleGuidance = false,
        )
        assertEquals(CrossingState.Red, decisionOf(TraversePhase.ATTENDRE).crossingState())
        assertEquals(CrossingState.Red, decisionOf(TraversePhase.ALERTE).crossingState())
        assertEquals(CrossingState.Green, decisionOf(TraversePhase.PRET).crossingState())
        assertEquals(CrossingState.Green, decisionOf(TraversePhase.TRAVERSER).crossingState())
    }

    @Test
    fun `veto plus vert parle rouge en chaine jusqu a la voix`() {
        val veto = TraverseVeto()
        val announcer = CrossingAnnouncer()
        val first = veto.update(true, listOf(detection("car", 0.9f, smallBox)), focalPx)
        assertEquals(CrossingState.Green, first.crossingState())
        assertEquals("You can cross", announcer.onFrame(0L, first.crossingState())?.phrase)
        val second = veto.update(true, listOf(detection("car", 0.9f, nearBox)), focalPx)
        assertEquals(CrossingState.Red, second.crossingState())
        assertNull("rouge pas encore confirmé", announcer.onFrame(200L, second.crossingState()))
        val third = veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 220f, 220f))), focalPx)
        val cue = announcer.onFrame(400L, third.crossingState())
        assertEquals("Don't cross yet", cue?.phrase)
        assertTrue("retour au rouge après vert : urgent", cue?.urgent ?: false)
        assertEquals(CrossingState.Red, cue?.state)
    }

    @Test
    fun `phrases prechauffees - litteraux anglais exacts`() {
        assertEquals("Don't cross yet", TraversePhrases.RED)
        assertEquals("You can cross", TraversePhrases.GREEN)
        assertEquals("Sidewalk across the street.", TraversePhrases.SIDEWALK_ACROSS)
        assertEquals("On the crosswalk.", TraversePhrases.ON_CROSSWALK)
        assertEquals(
            listOf(
                "Don't cross yet", "You can cross",
                "Sidewalk across the street.", "On the crosswalk.",
            ),
            TraversePhrases.ALL,
        )
    }
}
