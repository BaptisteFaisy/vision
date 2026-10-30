package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.SegObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le pipeline assemble hystérésis, veto et voix : la première frame cache,
 * la deuxième montre, le vert maintenu fait traverser, le veto en pleine
 * traversée coupe tout en urgent. Focale de test : 1000 px (une voiture de
 * 200 px de haut sort à 7,25 m, sous le seuil veto).
 */
class TraversePipelineTest {

    private val focalPx = 1000f
    private val mp3 = ByteArray(64) { 0xFF.toByte() }
    private val nearBox = listOf(0f, 0f, 200f, 200f)
    private val clips = mutableMapOf<String, String>()

    private fun seg(label: String = "car", score: Float = 0.9f): SegObject =
        SegObject(label, score, nearBox, "")

    private fun pipeline(
        player: FakePlayer = FakePlayer(),
        tts: FakeTts = FakeTts(Result.success(mp3)),
        withVoice: Boolean = true,
    ): TraversePipeline = TraversePipeline(voice = traverseVoice(player, tts, withVoice))

    private fun traverseVoice(player: FakePlayer, tts: FakeTts, withVoice: Boolean): TraverseVoice? =
        if (!withVoice) {
            null
        } else {
            TraverseVoice(tts, player, FakeSystemTts(store = clips), FakeStore(clips))
        }

    private suspend fun TraversePipeline.frame(
        objects: List<SegObject>,
        green: Boolean,
        index: Int,
    ): TraverseOutput = onFrame(objects, 640, 480, green, focalPx, index * 1000L)

    @Test
    fun `premiere frame - hysteresis cache, veto sans objet, silence`() = runBlocking {
        val out = pipeline().frame(listOf(seg()), green = true, index = 1)

        assertTrue(out.objects.isEmpty())
        assertTrue(out.detections.isEmpty())
        assertEquals(TraversePhase.PRET, out.decision.state)
        assertFalse(out.decision.veto)
        assertNull(out.cue)
        assertNull(out.voice)
    }

    @Test
    fun `deuxieme frame - piste confirmee, veto voit le vehicule proche`() = runBlocking {
        val pipe = pipeline()
        pipe.frame(listOf(seg()), green = true, index = 1)
        val out = pipe.frame(listOf(seg()), green = true, index = 2)

        assertEquals(1, out.objects.size)
        assertEquals(1, out.detections.size)
        assertEquals(640, out.detections.single().width)
        assertEquals(480, out.detections.single().height)
        assertTrue(out.decision.veto)
        assertEquals(TraversePhase.ALERTE, out.decision.state)
        assertEquals("car", out.decision.blockingLabel)
        assertTrue(out.decision.keepObstacleGuidance)
        assertNull("alerte d une seule frame : pas encore confirmee", out.cue)
    }

    @Test
    fun `vert maintenu sans veto - traversez parle une fois`() = runBlocking {
        val player = FakePlayer()
        val pipe = pipeline(player = player)
        var cues = 0
        repeat(6) { i ->
            val out = pipe.frame(emptyList(), green = true, index = i + 1)
            if (out.cue != null) {
                cues++
                assertEquals(CrossingText.phrase(CrossingState.Green), out.cue.phrase)
                assertFalse(out.cue.urgent)
                assertEquals(TraverseVoiceOutcome.PRIMARY, out.voice)
            }
        }
        assertEquals(1, cues)
        assertEquals(listOf("play"), player.events)
    }

    @Test
    fun `veto en pleine traversee - retour au rouge urgent et preemptif`() = runBlocking {
        val player = FakePlayer()
        val pipe = pipeline(player = player)
        repeat(5) { i -> pipe.frame(emptyList(), green = true, index = i + 1) }
        pipe.frame(listOf(seg()), green = true, index = 6)
        val alert = pipe.frame(listOf(seg()), green = true, index = 7)
        assertNull(alert.cue)
        val out = pipe.frame(listOf(seg()), green = true, index = 8)

        assertTrue(out.decision.veto)
        assertEquals(TraversePhase.ATTENDRE, out.decision.state)
        assertNotNull(out.cue)
        assertTrue(out.cue!!.urgent)
        assertEquals(CrossingText.phrase(CrossingState.Red), out.cue.phrase)
        assertEquals(TraverseVoiceOutcome.PRIMARY, out.voice)
        assertEquals(listOf("play", "stop", "play"), player.events)
    }

    @Test
    fun `sans voix - signal calcule mais rien de parle`() = runBlocking {
        val pipe = pipeline(withVoice = false)
        repeat(3) { i -> pipe.frame(emptyList(), green = true, index = i + 1) }
        val out = pipe.frame(emptyList(), green = true, index = 4)

        assertNotNull(out.cue)
        assertNull(out.voice)
    }

    @Test
    fun `repli hors-ligne - clip rejoue quand elevenlabs echoue`() = runBlocking {
        clips[CrossingText.phrase(CrossingState.Green)] = "/cache/vert.wav"
        val player = FakePlayer()
        val tts = FakeTts(Result.failure(RuntimeException("402")))
        val pipe = pipeline(player = player, tts = tts)
        repeat(3) { i -> pipe.frame(emptyList(), green = true, index = i + 1) }
        val out = pipe.frame(emptyList(), green = true, index = 4)

        assertEquals(TraverseVoiceOutcome.OFFLINE, out.voice)
        assertEquals("/cache/vert.wav", player.playedPath)
    }

    @Test
    fun `reset - pistes veto et annonces oublies`() = runBlocking {
        val pipe = pipeline()
        pipe.frame(listOf(seg()), green = true, index = 1)
        pipe.frame(listOf(seg()), green = true, index = 2)
        pipe.reset()
        val out = pipe.frame(listOf(seg()), green = true, index = 3)

        assertTrue(out.objects.isEmpty())
        assertEquals(TraversePhase.PRET, out.decision.state)
        assertNull(out.cue)
    }

    @Test
    fun `prompts - vehicules puis bordure et trottoir, bornes au plafond`() {
        assertEquals(VehicleConcepts.PROMPTS + listOf("curb", "sidewalk"), TraversePipeline.promptsFor())
        val prompts = TraversePipeline.promptsFor(listOf("traffic light"))
        assertEquals(VehicleConcepts.PROMPTS, prompts.take(7))
        assertEquals(listOf("curb", "sidewalk"), prompts.drop(7).take(2))
        assertEquals("traffic light", prompts.last())
        assertTrue(prompts.size <= LivePrompts.MAX)
        val full = TraversePipeline.promptsFor((1..20).map { "objet$it" })
        assertEquals(LivePrompts.MAX, full.size)
    }
}
