package dev.sam3vision.core.live

import dev.sam3vision.core.depth.DepthReadings
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mur et concepts = obstacles bloquants, de bout en bout : la chaîne de
 * production (`LiveProbe.publish`) rejouée en JVM — `DistanceMeter.measure`
 * (sténopé), affinage profondeur (`DepthReadings`, le cœur pur de
 * `ServerDepthMeter.readingsFor`), `placedLabels`, `Guidance.decide`, mot et
 * phrase.
 *
 * Physique assumée, pas contournée : un mur à 2 m remplit l'image (boîte
 * coupée haut/bas), donc le sténopé ne le mesure pas — le couloir bloque par
 * prudence avec le côté de sortie. Le contact à 0,5 m demande une mesure :
 * profondeur serveur (`with_depth`) ou petit objet
 * mesurable au sténopé — et le cap droit contourne toujours, même au
 * contact. Aucune distance inventée nulle part.
 */
class GuidanceObstaclesTest {

    private val focalPx = LensSpec.Default.focalPx(WIDTH, HEIGHT)

    private fun seg(label: String, box: List<Float>, depth: Float? = null): SegObject =
        SegObject(label, 0.9f, box, "", depth = depth)

    private fun frameOf(vararg objects: SegObject): LiveFrame =
        LiveFrame(WIDTH, HEIGHT, 0L, objects.toList())

    /**
     * Boîte qui mesure [meters] au sténopé, centrée en [centerX]. Le montage
     * se garde lui-même : une boîte coupée ferait échouer le test, pas la
     * mesure.
     */
    private fun boxFor(label: String, meters: Float, centerX: Float = 0.5f): List<Float> {
        val real = requireNotNull(KnownSizes.heightMeters(label)) { "sans taille : $label" }
        val heightPx = real * focalPx / meters
        val box = listOf(centerX * WIDTH - 50f, TOP_PX, centerX * WIDTH + 50f, TOP_PX + heightPx)
        assertFalse("montage coupé pour $label à $meters m", Pinhole.isVerticallyCut(box, HEIGHT))
        return box
    }

    /** Chaîne sténopé seul (réglages par défaut : pas de pose, pas de profondeur). */
    private fun pinholeChain(frame: LiveFrame): ChainOut {
        val detections = frame.objects.toDetections(frame.width, frame.height)
        val readings = DistanceMeter().measure(frame.width, frame.height, detections)
        val placed = placedLabels(frame, readings)
        return ChainOut(readings, placed, Guidance.decide(placed))
    }

    /**
     * Mur à 0,5 m via la profondeur serveur (`with_depth`) : la personne
     * calibre l'échelle (ancre sténopé), le mur — le plus proche, proximité
     * 1 — sort à 0,5 m. Les deux proximités restent dans le contrat 0..1.
     */
    private fun depthWallStop(): ChainOut {
        val personBox = boxFor("personne", ANCHOR_METERS, centerX = 0.12f)
        val wallBox = listOf(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        val frame = frameOf(
            seg("personne", personBox, depth = 0.5f / ANCHOR_METERS),
            seg("mur", wallBox, depth = 1f),
        )
        val detections = frame.objects.toDetections(frame.width, frame.height)
        val pinhole = DistanceMeter().measure(frame.width, frame.height, detections)
        val server = DepthReadings.serverCloseness(frame.objects)
        val refined = DepthReadings.refineWithCloseness(detections, pinhole, server)
        val placed = placedLabels(frame, refined)
        return ChainOut(refined, placed, Guidance.decide(placed))
    }

    /**
     * Poteau depth-only (sans hauteur sténopé) mesuré par la profondeur :
     * la personne à 3 m calibre l'échelle (ancre), le poteau sort à 4 m.
     */
    private fun depthPolePath(): ChainOut {
        val personBox = boxFor("personne", 3f, centerX = 0.55f)
        val poleBox = listOf(0.30f * WIDTH - 50f, TOP_PX, 0.30f * WIDTH + 50f, 300f)
        val frame = frameOf(
            seg("personne", personBox, depth = 0.8f),
            seg("poteau", poleBox, depth = 0.6f),
        )
        val detections = frame.objects.toDetections(frame.width, frame.height)
        val pinhole = DistanceMeter().measure(frame.width, frame.height, detections)
        val server = DepthReadings.serverCloseness(frame.objects)
        val refined = DepthReadings.refineWithCloseness(detections, pinhole, server)
        val placed = placedLabels(frame, refined)
        return ChainOut(refined, placed, Guidance.decide(placed))
    }

    private data class ChainOut(
        val readings: List<DistanceReading>,
        val placed: List<PlacedLabel>,
        val decision: GuidanceDecision,
    )

    @Test
    fun `mur à 2 m remplit l'image et bloque avec côté de sortie`() {
        assertEquals(2.50f, KnownSizes.heightMeters("mur")!!, 0.001f)
        val wallBox = listOf(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        val out = pinholeChain(frameOf(seg("mur", wallBox)))

        assertTrue(out.readings.isEmpty())
        assertNull(out.placed.single().meters)
        val decision = out.decision
        assertTrue(decision.blocked)
        assertEquals("mur", decision.obstacle!!.label)
        assertEquals(GuidanceSide.Left, decision.side)
        assertEquals(GuidanceWord.Left, Guidance.word(decision))
        assertEquals("Left.", Guidance.phrase(decision))
        assertEquals("Left.", Guidance.phrase(decision, SpeechLanguage.En))
        assertEquals("w|left", Guidance.key(decision))
        assertNull(decision.shiftMeters)
    }

    @Test
    fun `mur mesuré à 3 m bloque avec décalage chiffré`() {
        val out = pinholeChain(frameOf(seg("mur", boxFor("mur", 3f))))

        assertEquals(3f, out.readings.single().meters, 0.02f)
        assertTrue(out.decision.blocked)
        assertEquals(1f, out.decision.shiftMeters!!, 0.001f)
        assertEquals("Left.", Guidance.phrase(out.decision))
    }

    @Test
    fun `mur à 0,5 m mesuré par la profondeur contourne a droite`() {
        val out = depthWallStop()

        assertEquals(2, out.readings.size)
        val wall = out.readings.first { it.label == "mur" }
        assertEquals(0.5f, wall.meters, 0.02f)
        assertTrue(out.decision.blocked)
        assertEquals("mur", out.decision.obstacle!!.label)
        assertEquals(GuidanceWord.Right, Guidance.word(out.decision))
        assertEquals("Right.", Guidance.phrase(out.decision))
        assertEquals("Right.", Guidance.phrase(out.decision, SpeechLanguage.En))
        assertEquals("w|right", Guidance.key(out.decision))
    }

    @Test
    fun `petit objet à 0,5 m mesuré au sténopé contourne a gauche`() {
        val out = pinholeChain(frameOf(seg("chat", boxFor("chat", 0.5f))))

        assertEquals(0.5f, out.readings.single().meters, 0.02f)
        assertTrue(out.decision.blocked)
        assertEquals(GuidanceWord.Left, Guidance.word(out.decision))
        assertEquals("Left.", Guidance.phrase(out.decision))
    }

    @Test
    fun `barrière voiture vélo humain à 2 m bloquent mesurés`() {
        listOf("barrière", "voiture", "vélo", "humain").forEach { label ->
            val out = pinholeChain(frameOf(seg(label, boxFor(label, 2f))))

            assertEquals("lecture $label", 2f, out.readings.single().meters, 0.02f)
            assertTrue("bloqué $label", out.decision.blocked)
            assertEquals(label, out.decision.obstacle!!.label)
        }
    }

    /**
     * Escaliers et marches : sans hauteur sténopé (volontaire, voir
     * `KnownSizes`), ils bloquent par prudence. « marche » est reconnu bordure
     * côté `CurbConcepts` (audit 31/10), pas escalier — le couloir bloque
     * tout label, la reco ne le conditionne pas.
     */
    @Test
    fun `escaliers et marches sans distance bloquent par prudence`() {
        listOf("stairs", "escalier", "step", "steps", "marche").forEach { label ->
            assertNull("taille $label", KnownSizes.heightMeters(label))
            val out = pinholeChain(frameOf(seg(label, CENTER_BOX)))

            assertTrue("lecture $label", out.readings.isEmpty())
            assertTrue("bloqué $label", out.decision.blocked)
            assertEquals(GuidanceWord.Left, Guidance.word(out.decision))
            assertNull("décalage $label", out.decision.shiftMeters)
        }
    }

    /**
     * Trou : aucun concept, aucune taille inventée — la prudence bloque.
     * Une vraie distance demanderait la profondeur ou le plan de sol
     * (suivi, pas ici).
     */
    @Test
    fun `trou sans taille connue bloque par prudence sans distance inventée`() {
        assertNull(KnownSizes.heightMeters("trou"))
        assertNull(KnownSizes.heightMeters("hole"))
        val out = pinholeChain(frameOf(seg("trou", CENTER_BOX)))

        assertTrue(out.readings.isEmpty())
        assertTrue(out.decision.blocked)
        assertEquals("Left.", Guidance.phrase(out.decision))
    }

    @Test
    fun `poteau depth-only mesure par la profondeur participe au chemin optimal`() {
        assertNull(KnownSizes.heightMeters("poteau"))
        val out = depthPolePath()

        val pole = out.readings.first { it.label == "poteau" }
        assertEquals(4f, pole.meters, 0.05f)
        assertTrue(out.decision.blocked)
        // Chemin optimal (gauche) contre comptage (droite) : la mesure tranche.
        assertEquals(GuidanceSide.Left, out.decision.side)
        assertEquals("Gauche.", Guidance.phrase(out.decision, SpeechLanguage.Fr))
    }

    @Test
    fun `inconnu au sol avec pose mesure puis suit le chemin optimal`() {
        assertNull(KnownSizes.heightMeters("poteau"))
        val meter = DistanceMeter()
        meter.pose = CameraPose(1.5f, 0f)
        val poleBox = listOf(0.30f * WIDTH - 50f, 137f, 0.30f * WIDTH + 50f, 337f)
        val frame = frameOf(
            seg("poteau", poleBox),
            seg("personne", boxFor("personne", 2f, 0.55f)),
        )
        val detections = frame.objects.toDetections(frame.width, frame.height)
        val readings = meter.measure(frame.width, frame.height, detections)
        val pole = readings.first { it.label == "poteau" }

        assertEquals(4f, pole.meters, 0.1f)
        val decision = Guidance.decide(placedLabels(frame, readings))

        assertTrue(decision.blocked)
        // Chemin optimal (gauche) contre comptage (droite) : la mesure tranche.
        assertEquals(GuidanceSide.Left, decision.side)
        assertEquals("Gauche.", Guidance.phrase(decision, SpeechLanguage.Fr))
    }

    @Test
    fun `l'annonceur dit le mot du mur une seule fois`() {
        val announcer = GuidanceAnnouncer()
        val wallBox = listOf(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        val cut = pinholeChain(frameOf(seg("mur", wallBox))).placed

        assertEquals("left.", announcer.onFrame(0L, cut))
        assertNull(announcer.onFrame(100L, cut))
        assertEquals("right.", announcer.onFrame(200L, depthWallStop().placed))
    }

    companion object {
        private const val WIDTH = 512
        private const val HEIGHT = 384

        /** Haut de boîte des montages : loin du bord haut (marge 2 px). */
        private const val TOP_PX = 40f

        /** Distance de l'ancre personne qui calibre l'échelle profondeur. */
        private const val ANCHOR_METERS = 3f

        /** Boîte centrale nette (ni coupée, ni calibrée selon le label). */
        private val CENTER_BOX = listOf(206f, 100f, 306f, 300f)
    }
}
