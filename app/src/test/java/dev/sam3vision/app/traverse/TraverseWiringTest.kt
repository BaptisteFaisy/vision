package dev.sam3vision.app.traverse

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.TtsClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraverseClipStore
import dev.sam3vision.core.traverse.TraverseDecision
import dev.sam3vision.core.traverse.TraversePhase
import dev.sam3vision.core.traverse.TraversePipeline
import dev.sam3vision.core.traverse.TraversePlayer
import dev.sam3vision.core.traverse.TraverseSystemTts
import dev.sam3vision.core.traverse.TraverseVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Câblage traversée sans voix (voix `null`) : le filtre publie objets et
 * détections, le veto remonte les objets proches, l'arrêt est muet.
 * L'activation touche Android (TTS, cache) : couverte sur appareil seul.
 *
 * Avec voix attachée (fakes) : le vert maintenu sans véhicule fait parler
 * « Vert, traversez. », et sans vert ce mot d'ordre ne sort jamais.
 */
class TraverseWiringTest {

    private val box = listOf(0f, 0f, 200f, 200f)

    private fun seg(label: String = "car", score: Float = 0.9f): SegObject =
        SegObject(label, score, box, "")

    private fun frame(objects: List<SegObject>): LiveFrame = LiveFrame(640, 480, 0L, objects)

    private fun wiring(): TraverseWiring = TraverseWiring(TraversePipeline(), log = {})

    private suspend fun TraverseWiring.at(objects: List<SegObject>, index: Int): ShownFrame =
        filter(frame(objects), 640, 480, 1000f, index * 1000L, SpeechLanguage.Fr)

    @Test
    fun `filter - premiere frame cachee, silence`() = runBlocking {
        val shown = wiring().at(listOf(seg()), 1)

        assertTrue(shown.frame.objects.isEmpty())
        assertTrue(shown.detections.isEmpty())
        assertEquals(640, shown.frame.width)
        assertFalse(shown.keepGuidance)
    }

    @Test
    fun `filter - piste confirmee, veto proche remonte`() = runBlocking {
        val wiring = wiring()
        wiring.at(listOf(seg()), 1)
        val shown = wiring.at(listOf(seg()), 2)

        assertEquals(1, shown.frame.objects.size)
        assertEquals(1, shown.detections.size)
        assertEquals(640, shown.detections.single().width)
        assertEquals(480, shown.detections.single().height)
        assertTrue("voiture a 7 m : guidage obstacles garde", shown.keepGuidance)
    }

    @Test
    fun `setActive faux sur repos - sans effet ni relance`() {
        val wiring = wiring()
        var relaunches = 0
        var quiets = 0
        // L'app est inutilisée sur le chemin sans effet : `null` suffit en JVM.
        wiring.setActive(false, null, Settings.Default, scope(), listOf("car"), { relaunches++ }, { quiets++ })

        assertFalse(wiring.active)
        assertEquals(0, relaunches)
        assertEquals(0, quiets)
    }

    @Test
    fun `deactivate - pipeline efface et quiet appele`() = runBlocking {
        val wiring = wiring()
        wiring.at(listOf(seg()), 1)
        wiring.at(listOf(seg()), 2)
        wiring.updateLight(CrossingState.Green)
        var quiets = 0
        wiring.deactivate { quiets++ }

        assertFalse(wiring.active)
        assertEquals(1, quiets)
        assertFalse("vert oublie", wiring.lightGreen)
        assertTrue("pistes oubliees", wiring.at(listOf(seg()), 3).frame.objects.isEmpty())
    }

    @Test
    fun `reset - pistes oubliees sans toucher au mode`() = runBlocking {
        val wiring = wiring()
        wiring.at(listOf(seg()), 1)
        wiring.at(listOf(seg()), 2)
        wiring.updateLight(CrossingState.Green)
        wiring.reset()

        assertFalse(wiring.active)
        assertFalse("vert oublie", wiring.lightGreen)
        assertTrue(wiring.at(listOf(seg()), 3).frame.objects.isEmpty())
    }

    @Test
    fun `close sans activation - sans effet`() {
        val wiring = wiring()
        wiring.close()
        wiring.close()
        assertFalse(wiring.active)
    }

    @Test
    fun `updateLight - seul le vert autorise`() {
        val wiring = wiring()

        wiring.updateLight(CrossingState.Green)
        assertTrue(wiring.lightGreen)
        wiring.updateLight(CrossingState.Red)
        assertFalse(wiring.lightGreen)
        wiring.updateLight(CrossingState.Unknown)
        assertFalse(wiring.lightGreen)
    }

    @Test
    fun `vert maintenu sans vehicule - traversez parle une fois`() = runBlocking {
        val tts = FakeTts()
        val wiring = voicedWiring(tts)
        wiring.updateLight(CrossingState.Green)
        repeat(5) { wiring.at(emptyList(), it + 1) }

        assertEquals(listOf("Vert, traversez."), tts.asked)
    }

    @Test
    fun `sans vert - traversez jamais parle`() = runBlocking {
        val tts = FakeTts()
        val wiring = voicedWiring(tts)
        repeat(5) { wiring.at(emptyList(), it + 1) }

        assertFalse(tts.asked.contains("Vert, traversez."))
    }

    @Test
    fun `transitions phase - une ligne par phase`() = runBlocking {
        val lines = mutableListOf<String>()
        val wiring = TraverseWiring(TraversePipeline(), log = { lines += it })
        wiring.updateLight(CrossingState.Green)
        wiring.at(emptyList(), 1)
        wiring.at(emptyList(), 2)
        wiring.at(emptyList(), 3)

        assertEquals(
            listOf("traversee phase=PRET veto=false", "traversee phase=TRAVERSER veto=false"),
            lines,
        )
    }

    @Test
    fun `ligne simple - phase et veto seuls`() {
        val decision = TraverseDecision(TraversePhase.PRET, "prêt", false, null, null, null, false)

        assertEquals("traversee phase=PRET veto=false", traverseLogLine(decision))
    }

    @Test
    fun `ligne veto - label et mesures`() {
        val decision = TraverseDecision(TraversePhase.ALERTE, "attendez", true, "car", 8.2f, 2.1f, true)

        assertEquals("traversee phase=ALERTE veto=true blocking=car meters=8.2 ttc=2.1", traverseLogLine(decision))
    }

    @Test
    fun `deactivate - mode OFF journalise`() {
        val lines = mutableListOf<String>()
        val wiring = TraverseWiring(TraversePipeline(), log = { lines += it })
        wiring.deactivate { }

        assertEquals(listOf("traversee mode=OFF"), lines)
    }

    private fun voicedWiring(tts: FakeTts): TraverseWiring {
        val pipeline = TraversePipeline()
        pipeline.voice = TraverseVoice(tts, FakePlayer(), FakeSystemTts(), FakeStore())
        return TraverseWiring(pipeline, log = {})
    }

    private class FakeTts : TtsClient {
        val asked = mutableListOf<String>()
        override suspend fun synthesize(text: String): Result<ByteArray> {
            asked += text
            return Result.success(byteArrayOf(1))
        }
    }

    private class FakePlayer : TraversePlayer {
        override val isBusy: Boolean = false
        override fun stop() = Unit
        override suspend fun play(bytes: ByteArray): Boolean = true
        override suspend fun playFile(path: String): Boolean = false
    }

    private class FakeSystemTts : TraverseSystemTts {
        override suspend fun renderToFile(phrase: String, path: String): Result<String> =
            Result.failure(IllegalStateException("no tts"))

        override fun effectiveLanguage(): String = "fr-FR"
    }

    private class FakeStore : TraverseClipStore {
        override fun pathFor(phrase: String): String = "/tmp/clip.wav"
        override fun clipFor(phrase: String): String? = null
    }

    private fun scope(): CoroutineScope = CoroutineScope(EmptyCoroutineContext)
}
