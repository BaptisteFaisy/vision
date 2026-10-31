package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraverseVeto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingWiringTest {

    @Test
    fun `mode coupe libelle OFF`() {
        assertEquals("Crossing: OFF", crossingButtonLabel(false, null))
        assertEquals("Crossing: OFF", crossingButtonLabel(false, CrossingState.Green))
    }

    @Test
    fun `collecte - sans boite, pas de crop`() {
        // Pas de feu en vue : la collecte ne journalise rien (le rappel avec
        // boîte exige le décodage Bitmap, sur appareil seul).
        var calls = 0
        val light = crossingLightOf(LiveFrame(100, 100, 0L, emptyList()), "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertEquals(0, calls)
    }

    @Test
    fun `mode actif sans lecture dit rouge, binaire`() {
        assertEquals("Crossing: RED", crossingButtonLabel(true, null))
    }

    @Test
    fun `mode actif libelle l etat du feu`() {
        assertEquals("Crossing: GREEN", crossingButtonLabel(true, CrossingState.Green))
        assertEquals("Crossing: RED", crossingButtonLabel(true, CrossingState.Red))
    }

    @Test
    fun `bascule traversee garde les memes prompts, memes masques`() = runTest {
        val (probe, _) = probeHarness(this)
        val before = probe.state.value.conceptsSent
        probe.setCrossingActive(Settings.Default, true)
        assertTrue(probe.state.value.crossingActive)
        assertEquals(before, probe.state.value.conceptsSent)
        probe.setCrossingActive(Settings.Default, false)
        assertFalse(probe.state.value.crossingActive)
        assertEquals(before, probe.state.value.conceptsSent)
    }

    @Test
    fun `mode coupe ne publie ni phrase ni phase`() {
        // Sortie avant toute lecture : la sonde reste muette et le bandeau absent.
        val patch = crossingPatchOf(
            CrossingAnnouncer(), TraverseVeto(), false,
            LiveFrame(100, 100, 0L, emptyList()), LensSpec.Default, "", 0L, SpeechLanguage.Fr,
        )
        assertNull(patch)
    }

    @Test
    fun `patch actif - etat confirme rouge des la premiere frame`() {
        // Pas de boîte en vue : lecture rouge, rien d'annoncé → affiché rouge.
        val announcer = CrossingAnnouncer()
        val veto = TraverseVeto()
        val frame = LiveFrame(100, 100, 0L, emptyList())
        val patch = crossingPatchOf(
            announcer, veto, true, frame, LensSpec.Default, "", 0L, SpeechLanguage.Fr,
        )

        assertNull(patch?.cue)
        assertEquals(CrossingState.Red, patch?.state)
        assertNull(patch?.box)
    }

    @Test
    fun `patch actif - le rouge annonce parle a la deuxieme frame`() {
        val announcer = CrossingAnnouncer()
        val veto = TraverseVeto()
        val frame = LiveFrame(100, 100, 0L, emptyList())
        crossingPatchOf(announcer, veto, true, frame, LensSpec.Default, "", 0L, SpeechLanguage.Fr)
        val patch = crossingPatchOf(
            announcer, veto, true, frame, LensSpec.Default, "", 200L, SpeechLanguage.Fr,
        )

        assertEquals("Rouge, attendez.", patch?.cue?.phrase)
        assertEquals(CrossingState.Red, patch?.state)
    }

    @Test
    fun `vert sans boite - jamais une decision verte`() {
        // Fail-closed (ex-`boxOf()` null de la pompe locale) : seul un vert
        // AVEC boîte traverse ; seuils inchangés, c'est l'interprétation
        // qui verrouille, pas l'arbre ni la porte.
        assertFalse(CrossingLight(null, CrossingState.Green).greenForDecision)
        assertFalse(CrossingLight(null, CrossingState.Red).greenForDecision)
        assertFalse(CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.2f), CrossingState.Red).greenForDecision)
        assertTrue(CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.2f), CrossingState.Green).greenForDecision)
    }

    @Test
    fun `sans feu pieton - rouge, sans boite, pas de collecte`() {
        // Objets non-feu seuls : rien à lire → rouge, boîte nulle, rien à
        // journaliser. Les feux voiture ne sont jamais lus (31/10).
        var calls = 0
        val frame = LiveFrame(640, 480, 0L, listOf(SegObject("car", 0.9f, listOf(0f, 0f, 200f, 200f), "")))
        val light = crossingLightOf(frame, "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertNull(light.box)
        assertEquals(0, calls)
    }

    @Test
    fun `boite lue mais image illisible - rouge, boite gardee, pas de collecte`() {
        // JPEG vide : le crop échoue avant tout décodage (JVM-safe) → rouge,
        // la boîte reste pour l'overlay, rien à journaliser.
        var calls = 0
        val objects = listOf(SegObject("pedestrian traffic light", 0.9f, listOf(10f, 10f, 100f, 200f), ""))
        val light = crossingLightOf(LiveFrame(640, 480, 0L, objects), "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertNotNull(light.box)
        assertEquals(0, calls)
    }

    @Test
    fun `dimensions inconnues - rouge sans boite`() {
        // Taille de frame inconnue : aucune boîte normalisable → rouge, null.
        var calls = 0
        val objects = listOf(SegObject("pedestrian traffic light", 0.9f, listOf(10f, 10f, 100f, 200f), ""))
        val light = crossingLightOf(LiveFrame(0, 0, 0L, objects), "") { _, _, _ -> calls++ }

        assertEquals(CrossingState.Red, light.state)
        assertNull(light.box)
        assertEquals(0, calls)
    }
}
