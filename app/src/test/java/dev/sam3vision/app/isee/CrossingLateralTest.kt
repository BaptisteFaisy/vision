package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingArrivalWatcher
import dev.sam3vision.core.crossing.CrossingLateralAnnouncer
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Câblage latéral passage : centre, distance courte, patch mot + arrivée, Stop exclu. */
class CrossingLateralTest {

    private fun obj(label: String, box: List<Float>, score: Float = 0.8f) =
        SegObject(label = label, score = score, box = box, maskPngB64 = "")

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    @Test
    fun `centre du meilleur passage, nul sans passage`() {
        val box = listOf(156f, 700f, 356f, 1100f)
        val objects = listOf(obj("person", listOf(0f, 0f, 10f, 10f)), obj("crosswalk", box))
        assertEquals(0.5f, crossingCenterXOf(objects, 512)!!, 0.001f)
        assertNull(crossingCenterXOf(listOf(obj("person", box)), 512))
    }

    @Test
    fun `distance profondeur d abord, jamais stenope`() {
        val box = listOf(156f, 700f, 356f, 1100f)
        val readings = listOf(reading("crosswalk", 2.0f, box))
        val meters = crossingMetersOf(box, readings, CameraPose.Default, 516f, 569f, 1138)
        assertEquals(2.0f, meters!!, 0f)
    }

    @Test
    fun `distance sol en repli, nulle sans rien`() {
        val box = listOf(156f, 700f, 356f, 1100f)
        val ground = crossingMetersOf(box, emptyList(), CameraPose.Default, 516f, 569f, 1138)
        assertNotNull(ground)
        assertTrue(ground!! > 0f)
        assertNull(crossingMetersOf(box, emptyList(), null, 516f, 569f, 1138))
    }

    @Test
    fun `patch mot nouveau seul, clip guide`() {
        val lateral = CrossingLateralAnnouncer()
        val arrival = CrossingArrivalWatcher()
        val box = listOf(10f, 100f, 100f, 500f)
        val objects = listOf(obj("crosswalk", box))
        val first = crossingLateralPatchOf(
            lateral, arrival, objects, 512, 1138, emptyList(), null,
            LensSpec.Default, 0L, SpeechLanguage.Fr,
        )
        assertEquals("Gauche.", first?.lateral)
        assertEquals("guide_left", first?.lateralClip)
        val second = crossingLateralPatchOf(
            lateral, arrival, objects, 512, 1138, emptyList(), null,
            LensSpec.Default, 200L, SpeechLanguage.Fr,
        )
        assertNull(second?.lateral)
    }

    @Test
    fun `patch arrivee apres deux frames`() {
        val lateral = CrossingLateralAnnouncer()
        val arrival = CrossingArrivalWatcher()
        val box = listOf(156f, 700f, 356f, 1138f)
        val objects = listOf(obj("crosswalk", box))
        val first = crossingLateralPatchOf(
            lateral, arrival, objects, 512, 1138, emptyList(), null,
            LensSpec.Default, 0L, SpeechLanguage.Fr,
        )
        assertNull(first?.arrival)
        val second = crossingLateralPatchOf(
            lateral, arrival, objects, 512, 1138, emptyList(), null,
            LensSpec.Default, 200L, SpeechLanguage.Fr,
        )
        assertEquals("Sur le passage.", second?.arrival)
    }

    @Test
    fun `patch nul sans passage ou taille nulle`() {
        val lateral = CrossingLateralAnnouncer()
        val arrival = CrossingArrivalWatcher()
        val box = listOf(156f, 700f, 356f, 1100f)
        assertNull(
            crossingLateralPatchOf(
                lateral, arrival, listOf(obj("person", box)), 512, 1138,
                emptyList(), null, LensSpec.Default, 0L, SpeechLanguage.Fr,
            ),
        )
        assertNull(
            crossingLateralPatchOf(
                lateral, arrival, listOf(obj("crosswalk", box)), 0, 1138,
                emptyList(), null, LensSpec.Default, 0L, SpeechLanguage.Fr,
            ),
        )
    }

    @Test
    fun `crosswalk filtre avant guidage, jamais stop`() {
        val placed = listOf(
            PlacedLabel("crosswalk", 0.5f, 0.5f, 0.4f),
            PlacedLabel("mur", 0.5f, 0.5f, 0.4f),
        )
        val kept = placed.withoutCrosswalk()
        assertEquals(1, kept.size)
        assertEquals("mur", kept.single().label)
    }

    @Test
    fun `observe mot via clip et arrivee via phrase`() = runTest {
        val patches = MutableStateFlow<CrossingLateralPatch?>(null)
        val words = mutableListOf<Pair<String, String>>()
        val said = mutableListOf<String>()
        observeCrossingLateral(patches, backgroundScope, { clip, text -> words.add(clip to text) }, { said.add(it) })

        patches.value = CrossingLateralPatch("Gauche.", "guide_left", null, 0.1f, 5f)
        runCurrent()
        patches.value = CrossingLateralPatch(null, null, "Sur le passage.", 0.5f, 1.0f)
        runCurrent()
        patches.value = CrossingLateralPatch(null, null, null, 0.5f, 5f)
        runCurrent()

        assertEquals(listOf("guide_left" to "Gauche."), words)
        assertEquals(listOf("Sur le passage."), said)
    }

    @Test
    fun `feed publie mot hors traversee, retombe en traversee`() {
        CrossingLateralWatch.reset()
        val box = listOf(10f, 100f, 100f, 500f)
        val objects = listOf(obj("crosswalk", box))
        feedCrossingLateral(false, objects, 512, 1138, emptyList(), null, LensSpec.Default, 0L, SpeechLanguage.Fr)
        assertEquals("Gauche.", CrossingLateralWatch.flow.value?.lateral)
        feedCrossingLateral(true, objects, 512, 1138, emptyList(), null, LensSpec.Default, 200L, SpeechLanguage.Fr)
        assertNull(CrossingLateralWatch.flow.value)
    }

    @Test
    fun `lookup offre, profondeur lue ou nulle sans mesure`() {
        val box = listOf(156f, 700f, 356f, 1100f)
        val objects = listOf(obj("crosswalk", box))
        val near = crossingOfferMeters(listOf(reading("crosswalk", 1.5f, box)), null, LensSpec.Default, 512, 1138)
        assertEquals(1.5f, near(objects[0])!!, 0f)
        val unknown = crossingOfferMeters(emptyList(), null, LensSpec.Default, 512, 1138)
        assertNull(unknown(objects[0]))
    }

    @Test
    fun `feed silencieux sans passage`() {
        CrossingLateralWatch.reset()
        val box = listOf(10f, 100f, 100f, 500f)
        feedCrossingLateral(
            false, listOf(obj("person", box)), 512, 1138, emptyList(), null,
            LensSpec.Default, 0L, SpeechLanguage.Fr,
        )
        assertNull(CrossingLateralWatch.flow.value)
    }

    @Test
    fun `feed en traversee guide passage proche, tait passage loin`() {
        CrossingLateralWatch.reset()
        val box = listOf(10f, 100f, 100f, 500f)
        val objects = listOf(obj("crosswalk", box))
        feedCrossingLateral(
            true, objects, 512, 1138, listOf(reading("crosswalk", 2.5f, box)), null,
            LensSpec.Default, 0L, SpeechLanguage.Fr,
        )
        assertEquals("Gauche.", CrossingLateralWatch.flow.value?.lateral)
        feedCrossingLateral(
            true, objects, 512, 1138, listOf(reading("crosswalk", 5.0f, box)), null,
            LensSpec.Default, 200L, SpeechLanguage.Fr,
        )
        assertNull(CrossingLateralWatch.flow.value)
    }

    @Test
    fun `feed en traversee prouve sous les pieds sans mesure`() {
        CrossingLateralWatch.reset()
        val box = listOf(0f, 900f, 512f, 1137f)
        feedCrossingLateral(
            true, listOf(obj("crosswalk", box)), 512, 1138, emptyList(), null,
            LensSpec.Default, 0L, SpeechLanguage.Fr,
        )
        assertEquals("Tout droit.", CrossingLateralWatch.flow.value?.lateral)
    }
}
