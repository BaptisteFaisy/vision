package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidage latéral vers le passage : couloir, mot nouveau seul, distance courte, Stop exclu. */
class CrossingGuidanceTest {

    private val frameW = 512
    private val frameH = 1138

    private fun obj(label: String, score: Float = 0.8f, box: List<Float> = listOf(0f, 0f, 512f, 800f)) =
        SegObject(label = label, score = score, box = box, maskPngB64 = "")

    @Test
    fun `couloir 034-066 decide gauche droite tout droit`() {
        assertEquals(SteeringCap.Left, CrossingGuidance.directionOf(0.10f))
        assertEquals(SteeringCap.Left, CrossingGuidance.directionOf(0.33f))
        assertEquals(SteeringCap.Straight, CrossingGuidance.directionOf(0.34f))
        assertEquals(SteeringCap.Straight, CrossingGuidance.directionOf(0.50f))
        assertEquals(SteeringCap.Straight, CrossingGuidance.directionOf(0.66f))
        assertEquals(SteeringCap.Right, CrossingGuidance.directionOf(0.67f))
        assertEquals(SteeringCap.Right, CrossingGuidance.directionOf(0.90f))
    }

    @Test
    fun `couloir miroir du guidage obstacles`() {
        assertEquals(PositionText.SIDE_EDGE, CrossingGuidance.LEFT_EDGE, 0f)
        assertEquals(1f - PositionText.SIDE_EDGE, CrossingGuidance.RIGHT_EDGE, 0.001f)
    }

    @Test
    fun `mot du centre, jamais stop`() {
        assertEquals(GuidanceWord.Left, CrossingGuidance.wordOf(0.10f))
        assertEquals(GuidanceWord.Straight, CrossingGuidance.wordOf(0.50f))
        assertEquals(GuidanceWord.Right, CrossingGuidance.wordOf(0.90f))
        assertEquals("guide_left", CrossingGuidance.wordOf(0.10f).clipId)
        assertEquals("guide_straight", CrossingGuidance.wordOf(0.50f).clipId)
        assertEquals("guide_right", CrossingGuidance.wordOf(0.90f).clipId)
    }

    @Test
    fun `meilleur passage au meilleur score, seuil 035`() {
        val low = obj("crosswalk", 0.20f)
        val mid = obj("crosswalk", 0.55f, listOf(0f, 0f, 100f, 100f))
        val top = obj("Crosswalk", 0.89f, listOf(0f, 0f, 200f, 200f))
        val other = obj("sidewalk", 0.95f)
        assertEquals(top, CrossingGuidance.selectBest(listOf(low, mid, top, other)))
        assertNull(CrossingGuidance.selectBest(listOf(low, other)))
        assertNull(CrossingGuidance.selectBest(emptyList()))
    }

    @Test
    fun `sans dimensions le meilleur score gagne, repli historique`() {
        val off = obj("crosswalk", 0.90f, listOf(400f, 700f, 500f, 900f))
        val on = obj("crosswalk", 0.50f, listOf(100f, 700f, 412f, 1000f))
        assertEquals(off, CrossingGuidance.selectBest(listOf(off, on)))
    }

    @Test
    fun `avec dimensions le hors axe fort ne vole plus le guidage`() {
        val off = obj("crosswalk", 0.90f, listOf(400f, 700f, 500f, 900f))
        val on = obj("crosswalk", 0.50f, listOf(100f, 700f, 412f, 1000f))
        assertEquals(on, CrossingGuidance.selectBest(listOf(off, on), frameW = frameW, frameH = frameH))
    }

    @Test
    fun `avec dimensions le vertical est rejete`() {
        val tall = obj("crosswalk", 0.90f, listOf(200f, 400f, 280f, 1000f))
        assertNull(CrossingGuidance.selectBest(listOf(tall), frameW = frameW, frameH = frameH))
    }

    @Test
    fun `avec dimensions le minuscule est rejete`() {
        val tiny = obj("crosswalk", 0.90f, listOf(250f, 900f, 270f, 920f))
        assertNull(CrossingGuidance.selectBest(listOf(tiny), frameW = frameW, frameH = frameH))
    }

    @Test
    fun `avec dimensions le haut du cadre est rejete`() {
        val high = obj("crosswalk", 0.90f, listOf(100f, 100f, 412f, 300f))
        assertNull(CrossingGuidance.selectBest(listOf(high), frameW = frameW, frameH = frameH))
    }

    @Test
    fun `avec dimensions le meilleur score departage les plausibles`() {
        val small = obj("crosswalk", 0.50f, listOf(100f, 700f, 412f, 1000f))
        val big = obj("crosswalk", 0.70f, listOf(50f, 800f, 462f, 1100f))
        assertEquals(big, CrossingGuidance.selectBest(listOf(small, big), frameW = frameW, frameH = frameH))
    }

    @Test
    fun `plausible - geometrie illisible fausse`() {
        assertFalse(CrossingGuidance.isPlausibleCrosswalk(listOf(1f, 2f), frameW, frameH))
        assertFalse(CrossingGuidance.isPlausibleCrosswalk(listOf(0f, 0f, 100f, 100f), 0, frameH))
        assertFalse(CrossingGuidance.isPlausibleCrosswalk(listOf(0f, 0f, 100f, 100f), frameW, 0))
        assertFalse(CrossingGuidance.isPlausibleCrosswalk(listOf(0f, 0f, Float.NaN, 100f), frameW, frameH))
        assertFalse(CrossingGuidance.isPlausibleCrosswalk(listOf(300f, 700f, 100f, 1000f), frameW, frameH))
    }

    @Test
    fun `centre pixels et normalise, illisible nul`() {
        assertEquals(0.5f, CrossingGuidance.centerXOf(listOf(156f, 0f, 356f, 800f), 512)!!, 0.001f)
        assertEquals(0.25f, CrossingGuidance.centerXOfNormalized(listOf(0.10f, 0f, 0.40f, 0.9f))!!, 0.001f)
        assertNull(CrossingGuidance.centerXOf(listOf(1f, 2f), 512))
        assertNull(CrossingGuidance.centerXOf(listOf(0f, 0f, 10f, 10f), 0))
        assertNull(CrossingGuidance.centerXOfNormalized(listOf(0.1f)))
    }

    @Test
    fun `crosswalk exclu du stop, avant guidage`() {
        val placed = listOf(
            PlacedLabel("crosswalk", 0.5f, 0.5f, 0.4f),
            PlacedLabel("personne", 0.5f, 0.5f, 0.4f),
            PlacedLabel("Passage piéton", 0.2f, 0.5f, 2f),
        )
        val kept = CrossingGuidance.withoutCrosswalk(placed)
        assertEquals(1, kept.size)
        assertEquals("personne", kept.single().label)
        assertTrue(CrossingGuidance.withoutCrosswalk(emptyList()).isEmpty())
    }

    @Test
    fun `crosswalk jamais stenope, profondeur ou sol seuls`() {
        assertNull(KnownSizes.heightMeters("crosswalk"))
        assertNull(KnownSizes.heightMeters("Passage piéton"))
        assertTrue(KnownSizes.DEPTH_ONLY.contains("crosswalk"))
    }

    @Test
    fun `distance profondeur d abord, sol ensuite, jamais inventee`() {
        assertEquals(2.0f, CrossingDistance.bestMeters(2.0f, 5.0f)!!, 0f)
        assertEquals(5.0f, CrossingDistance.bestMeters(null, 5.0f)!!, 0f)
        assertEquals(5.0f, CrossingDistance.bestMeters(Float.NaN, 5.0f)!!, 0f)
        assertNull(CrossingDistance.bestMeters(null, null))
        assertNull(CrossingDistance.bestMeters(-1f, Float.NaN))
    }

    @Test
    fun `sol invalide ne mesure pas`() {
        assertNull(CrossingDistance.groundMeters(900f, CameraPose(0f), 1000f, 720f, 1440))
        assertNull(CrossingDistance.groundMeters(900f, CameraPose.Default, 0f, 720f, 1440))
    }

    @Test
    fun `lateral parle des la premiere frame, mot nouveau seul`() {
        val announcer = CrossingLateralAnnouncer()
        assertEquals("Straight.", announcer.onFrame(0L, 0.50f))
        assertNull(announcer.onFrame(200L, 0.52f))
        assertEquals("Left.", announcer.onFrame(400L, 0.10f))
        assertNull(announcer.onFrame(600L, 0.12f))
        assertEquals("Right.", announcer.onFrame(800L, 0.90f))
    }

    @Test
    fun `lateral sans delai ni stabilite, comme le guidage`() {
        val announcer = CrossingLateralAnnouncer(cooldownMs = 60_000L)
        assertEquals("Straight.", announcer.onFrame(0L, 0.50f))
        assertEquals("Left.", announcer.onFrame(100L, 0.10f))
    }

    @Test
    fun `lateral absence fait retomber, retour reparle`() {
        val announcer = CrossingLateralAnnouncer()
        assertEquals("Straight.", announcer.onFrame(0L, 0.50f))
        assertNull(announcer.onFrame(200L, null))
        assertEquals("Straight.", announcer.onFrame(400L, 0.50f))
    }

    @Test
    fun `lateral expose mot et phrase, reset re-arme`() {
        val announcer = CrossingLateralAnnouncer()
        assertNull(announcer.lastWord)
        announcer.onFrame(0L, 0.10f)
        assertEquals(GuidanceWord.Left, announcer.lastWord)
        assertEquals("Left.", announcer.lastPhrase)
        announcer.reset()
        assertNull(announcer.lastWord)
        assertEquals("Left.", announcer.onFrame(200L, 0.10f))
    }

    @Test
    fun `lateral anglais dit left right straight`() {
        val announcer = CrossingLateralAnnouncer()
        assertEquals("Left.", announcer.onFrame(0L, 0.10f, SpeechLanguage.En))
        assertEquals("Straight.", announcer.onFrame(200L, 0.50f, SpeechLanguage.En))
    }

    @Test
    fun `phrases approche FR courtes avec distance`() {
        assertEquals(
            "Passage piéton à gauche, à 2 mètres.",
            CrossingText.approach(SteeringCap.Left, 2.0f, SpeechLanguage.Fr),
        )
        assertEquals(
            "Passage piéton à droite, à 1 mètre.",
            CrossingText.approach(SteeringCap.Right, 1.0f, SpeechLanguage.Fr),
        )
        assertEquals(
            "Passage piéton devant, à 3 mètres.",
            CrossingText.approach(SteeringCap.Straight, 3.0f, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `phrases approche EN courtes avec distance`() {
        assertEquals(
            "Crosswalk to the left, 2 meters away.",
            CrossingText.approach(SteeringCap.Left, 2.0f, SpeechLanguage.En),
        )
        assertEquals(
            "Crosswalk ahead, 1 meter away.",
            CrossingText.approach(SteeringCap.Straight, 1.0f, SpeechLanguage.En),
        )
    }

    @Test
    fun `approche sans distance dit direction seule, jamais chiffree`() {
        assertEquals(
            "Passage piéton à gauche.",
            CrossingText.approach(SteeringCap.Left, null, SpeechLanguage.Fr),
        )
        assertEquals(
            "Crosswalk ahead.",
            CrossingText.approach(SteeringCap.Straight, Float.NaN, SpeechLanguage.En),
        )
        assertFalse(CrossingText.approach(SteeringCap.Right, null).contains("mètre"))
    }

    @Test
    fun `approche cadran optionnel withHour`() {
        assertEquals(
            "Passage piéton à gauche, à 2 mètres, à 10 heures.",
            CrossingText.approach(SteeringCap.Left, 2.0f, SpeechLanguage.Fr, 0.20f, withHour = true),
        )
        assertEquals(
            "Passage piéton à gauche, à 2 mètres.",
            CrossingText.approach(SteeringCap.Left, 2.0f, SpeechLanguage.Fr, 0.20f, withHour = false),
        )
        assertEquals(
            "Passage piéton à gauche, à 2 mètres.",
            CrossingText.approach(SteeringCap.Left, 2.0f, SpeechLanguage.Fr, null, withHour = true),
        )
    }

    @Test
    fun `anglais defaut de l approche`() {
        assertEquals(
            "Crosswalk ahead, 2 meters away.",
            CrossingText.approach(SteeringCap.Straight, 2.0f),
        )
    }

    @Test
    fun `guidage traverse jusqu a 6 m, borne incluse`() {
        assertEquals(6.0f, CrossingGuidance.GUIDE_METERS, 0f)
    }
}
