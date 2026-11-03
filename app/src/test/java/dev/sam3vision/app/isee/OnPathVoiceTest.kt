package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Voix des occupants du passage piéton (mode Traversée, dehors) : sur le
 * passage, le mot d'évitement parle à 100 (clip existant `guide_left` /
 * `guide_right`) — devant le verdict maintenu et les humains hors passage
 * (101), derrière le verdict changé (99). Entrée prouvée (arrivée annoncée
 * + proximité ≤ 2 m ou boîte sous les pieds), sortie retardée (4 frames
 * sans + timeout 4 s) : en cas de doute on reste sur passage.
 */
class OnPathVoiceTest {

    /** Porte-parole faux : dits enregistrés (niveau + texte). */
    private class FakeBusSpeaker : VoiceBusSpeaker {
        data class Said(val level: Int, val text: String)

        val said = mutableListOf<Said>()
        override fun idle(): Boolean = true
        override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
            said += Said(level, text)
        }

        override fun cut() = Unit
    }

    private val speaker = FakeBusSpeaker()
    private val width = 1000
    private val height = 1000
    private val cross = listOf(100f, 600f, 900f, 950f)

    @Before
    fun brancher() {
        OnPathWatch.reset()
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
        OnPathWatch.reset()
    }

    private fun obj(label: String, box: List<Float>, score: Float = 0.8f) =
        SegObject(label, score, box, "")

    private fun crosswalk() = obj("crosswalk", cross)

    /** Personne à gauche sur le passage (centre 0,4, pieds à 0,8). */
    private fun personLeft() = obj("person", listOf(350f, 400f, 450f, 800f))

    /** Personne à droite sur le passage (centre 0,6, pieds à 0,8). */
    private fun personRight() = obj("person", listOf(550f, 400f, 650f, 800f))

    private fun reading(label: String, box: List<Float>, meters: Float) =
        DistanceReading(-1, label, meters, meters, box)

    private fun feed(
        objects: List<SegObject>,
        atMs: Long,
        readings: List<DistanceReading> = emptyList(),
    ): Boolean = OnPathWatch.update(
        true, objects, width, height, readings, null, LensSpec.Default, atMs, SpeechLanguage.Fr,
    )

    /** État sonde minimal : traversée + calque distances. */
    private fun etat(readings: List<DistanceReading>, onCrosswalk: Boolean) = LiveProbeState(
        frames = 1, width = width, height = height, crossingActive = true,
        onCrosswalk = onCrosswalk, distanceLayer = DistanceLayer(width, height, readings),
    )

    @Test
    fun `occupant gauche = mot Droite clip guide_right niveau 100`() {
        val wish = onPathWish(SteeringCap.Right, SpeechLanguage.Fr)
        assertEquals(CrossingLevels.VERDICT, wish?.level)
        assertEquals(100, wish?.level)
        assertEquals("Droite.", wish?.text)
        assertEquals("guide_right", wish?.clipId)
        assertEquals(KEY_ONPATH_AVOID, wish?.key)
    }

    @Test
    fun `occupant droit = mot Gauche clip guide_left niveau 100`() {
        val wish = onPathWish(SteeringCap.Left, SpeechLanguage.En)
        assertEquals(100, wish?.level)
        assertEquals("Left.", wish?.text)
        assertEquals("guide_left", wish?.clipId)
    }

    @Test
    fun `sans cote conseille = silence`() {
        assertNull(onPathWish(null, SpeechLanguage.Fr))
        assertNull("tout droit : jamais un evitement", onPathWish(SteeringCap.Straight, SpeechLanguage.Fr))
    }

    @Test
    fun `arbitrage sur passage = 99 changement puis 100 occupants puis 101`() {
        val alert = VoiceWish(99, "Do not cross.", "verdict", "verdict_dont_cross")
        val avoid = onPathWish(SteeringCap.Right, SpeechLanguage.Fr)
        val human = VoiceWish(101, "Gauche.", "human-imminent", "guide_left")
        assertEquals(99, decideFrame(listOf(alert, avoid, human))?.level)
        assertEquals(100, decideFrame(listOf(avoid, human))?.level)
        assertEquals(101, decideFrame(listOf(human))?.level)
        assertNull(decideFrame(listOf(null, null)))
    }

    @Test
    fun `hors traversee = faux et memoire oubliee`() {
        feed(listOf(crosswalk()), 0L)
        assertFalse(OnPathWatch.update(false, listOf(crosswalk()), width, height, emptyList(), null, LensSpec.Default, 200L, SpeechLanguage.Fr))
        assertNull(OnPathWatch.side())
        assertFalse("1 frame apres oubli : pas encore sur passage", feed(listOf(crosswalk()), 400L))
    }

    @Test
    fun `entree = 2 frames arrivee + proximite, cote conseille a la 2e`() {
        val objects = listOf(crosswalk(), personLeft())
        assertFalse(feed(objects, 0L))
        assertNull("pas conseille avant l entree", OnPathWatch.side())
        assertTrue(feed(objects, 200L))
        assertEquals(SteeringCap.Right, OnPathWatch.side())
        assertTrue(feed(objects, 400L))
    }

    @Test
    fun `arrivee sans proximite = pas sur passage, latch garde`() {
        val far = listOf(reading("crosswalk", cross, 5f))
        assertFalse(feed(listOf(crosswalk()), 0L, far))
        assertFalse("arrive mais loin : pas sur passage", feed(listOf(crosswalk()), 200L, far))
        assertTrue("arrivee latchee + proche = sur passage", feed(listOf(crosswalk()), 400L))
    }

    @Test
    fun `4 frames vides = sortie, 1 retour avant = maintien`() {
        feed(listOf(crosswalk()), 0L)
        assertTrue(feed(listOf(crosswalk()), 200L))
        assertTrue(feed(emptyList(), 400L))
        assertTrue(feed(emptyList(), 600L))
        assertTrue(feed(emptyList(), 800L))
        assertTrue("retour avant la 4e : maintien", feed(listOf(crosswalk()), 1_000L))
        feed(emptyList(), 1_200L)
        feed(emptyList(), 1_400L)
        feed(emptyList(), 1_600L)
        assertFalse("4e vide : sortie", feed(emptyList(), 1_800L))
        assertNull(OnPathWatch.side())
    }

    @Test
    fun `timeout 4s sans passage = sortie avant les 4 frames`() {
        feed(listOf(crosswalk()), 0L)
        assertTrue(feed(listOf(crosswalk()), 200L))
        assertFalse(feed(emptyList(), 4_200L))
    }

    @Test
    fun `bout en bout sur passage = Droite niveau 100 devant humain 101`() {
        feed(listOf(crosswalk(), personLeft()), 0L)
        feed(listOf(crosswalk(), personLeft()), 200L)
        val watchers = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(
            etat(listOf(reading("person", personLeft().box, 2f)), onCrosswalk = true),
            { SpeechLanguage.Fr }, watchers,
        )

        assertEquals(listOf(FakeBusSpeaker.Said(100, "Droite.")), speaker.said)
    }

    @Test
    fun `dedans = occupants tus, silence total en traversee`() {
        feed(listOf(crosswalk(), personLeft()), 0L)
        feed(listOf(crosswalk(), personLeft()), 200L)
        val watchers = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(
            etat(listOf(reading("person", personLeft().box, 2f)), onCrosswalk = true),
            { SpeechLanguage.Fr }, watchers, placeMode = { PictoPlaceMode.DEDANS },
        )

        assertTrue(speaker.said.isEmpty())
    }

    @Test
    fun `hors passage = humain 101 inchange`() {
        val watchers = ObstacleWatchers(CurbWatcher(), HazardWatcher())

        obstacleFrame(
            etat(listOf(reading("person", personLeft().box, 2f)), onCrosswalk = false),
            { SpeechLanguage.Fr }, watchers,
        )

        assertEquals(listOf(FakeBusSpeaker.Said(101, "Droite.")), speaker.said)
    }
}
