package dev.sam3vision.app.traverse

import android.app.Application
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraversePipeline
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
 */
class TraverseWiringTest {

    private val box = listOf(0f, 0f, 200f, 200f)

    private fun seg(label: String = "car", score: Float = 0.9f): SegObject =
        SegObject(label, score, box, "")

    private fun frame(objects: List<SegObject>): LiveFrame = LiveFrame(640, 480, 0L, objects)

    private fun wiring(): TraverseWiring = TraverseWiring(TraversePipeline())

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
        wiring.setActive(false, null as Application, Settings.Default, scope(), listOf("car"), { relaunches++ }, { quiets++ })

        assertFalse(wiring.active)
        assertEquals(0, relaunches)
        assertEquals(0, quiets)
    }

    @Test
    fun `deactivate - pipeline efface et quiet appele`() = runBlocking {
        val wiring = wiring()
        wiring.at(listOf(seg()), 1)
        wiring.at(listOf(seg()), 2)
        var quiets = 0
        wiring.deactivate { quiets++ }

        assertFalse(wiring.active)
        assertEquals(1, quiets)
        assertTrue("pistes oubliees", wiring.at(listOf(seg()), 3).frame.objects.isEmpty())
    }

    @Test
    fun `reset - pistes oubliees sans toucher au mode`() = runBlocking {
        val wiring = wiring()
        wiring.at(listOf(seg()), 1)
        wiring.at(listOf(seg()), 2)
        wiring.reset()

        assertFalse(wiring.active)
        assertTrue(wiring.at(listOf(seg()), 3).frame.objects.isEmpty())
    }

    @Test
    fun `close sans activation - sans effet`() {
        val wiring = wiring()
        wiring.close()
        wiring.close()
        assertFalse(wiring.active)
    }

    private fun scope(): CoroutineScope = CoroutineScope(EmptyCoroutineContext)
}
