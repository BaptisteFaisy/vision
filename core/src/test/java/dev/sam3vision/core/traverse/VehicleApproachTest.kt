package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.predict.BoxPredictor
import dev.sam3vision.core.predict.MotionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approche véhicules sur boîtes SAM : association IoU entre frames, vitesse
 * de largeur lissée ([MotionModel] réutilisé), temps-avant-collision
 * tau = largeur / vitesse. Séquences synthétiques, aucune donnée réelle.
 */
class VehicleApproachTest {

    @Test
    fun `voiture qui approche - veto a la 6e frame avec TTC`() {
        val approach = VehicleApproach()
        listOf(100f, 110f, 120f, 130f, 140f).forEachIndexed { index, width ->
            assertFalse("run ${index + 1} < 5 : le tau attend (flicker)",
                approach.update(listOf(car(width)), atMs = index * 100L).approaching)
        }
        val sixth = approach.update(listOf(car(150f)), atMs = 500L)

        assertTrue("croissance lisse + vitesse établie", sixth.approaching)
        assertNotNull(sixth.ttcSeconds)
        assertTrue("tau ~1,8 s", sixth.ttcSeconds!! in 1.0f..3.0f)
        assertEquals("car", sixth.label)
    }

    @Test
    fun `flicker asymetrique - pas de veto tau`() {
        // Miroir trace G1w (S1 figé) : ±30 px alternés, jamais 3 signes +.
        val approach = VehicleApproach()
        val widths = listOf(40f, 72f, 60f, 92f, 80f, 112f)
        widths.forEachIndexed { index, width ->
            assertFalse(
                "flicker frame ${index + 1} : pas d'approche",
                approach.update(listOf(car(width)), atMs = index * 170L).approaching,
            )
        }
    }

    @Test
    fun `croissance franche - veto tau tenu des la 6e frame`() {
        val approach = VehicleApproach()
        val frames = listOf(100f, 110f, 120f, 130f, 140f, 150f, 160f)
        frames.forEachIndexed { index, width ->
            val verdict = approach.update(listOf(car(width)), atMs = index * 100L)
            if (index < 5) assertFalse("run < 5 : silence", verdict.approaching)
            else assertTrue("croissance franche : veto", verdict.approaching)
        }
    }

    @Test
    fun `voiture qui s eloigne - jamais de veto`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(120f)), atMs = 0L)
        approach.update(listOf(car(110f)), atMs = 100L)
        val third = approach.update(listOf(car(100f)), atMs = 200L)

        assertFalse(third.approaching)
        assertNull(third.ttcSeconds)
    }

    @Test
    fun `voiture arretee - jamais de veto`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.update(listOf(car(100f)), atMs = 100L)
        val third = approach.update(listOf(car(100f)), atMs = 200L)

        assertFalse(third.approaching)
    }

    @Test
    fun `deux vehicules - pistes independantes, le plus dangereux gagne`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f), busStatic()), atMs = 0L)
        approach.update(listOf(car(110f), busStatic()), atMs = 100L)
        approach.update(listOf(car(120f), busStatic()), atMs = 200L)
        approach.update(listOf(car(130f), busStatic()), atMs = 300L)
        approach.update(listOf(car(140f), busStatic()), atMs = 400L)
        val sixth = approach.update(listOf(car(150f), busStatic()), atMs = 500L)

        assertTrue(sixth.approaching)
        assertEquals("car", sixth.label)
    }

    @Test
    fun `piste perimee - oubliee, pas de veto fantome`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.update(listOf(car(110f)), atMs = 100L)
        approach.update(listOf(car(120f)), atMs = 200L)
        val late = approach.update(listOf(carFar()), atMs = 2000L)

        assertFalse("piste initiale périmée, nouvelle piste sans vitesse", late.approaching)
        assertEquals(1, approach.trackCount)
    }

    @Test
    fun `toute petite boite - ignoree malgre la croissance`() {
        val approach = VehicleApproach()
        approach.update(listOf(tiny(4f)), atMs = 0L)
        approach.update(listOf(tiny(6f)), atMs = 100L)
        val third = approach.update(listOf(tiny(8f)), atMs = 200L)

        assertFalse("moins de 2 % de la largeur : bruit lointain", third.approaching)
    }

    @Test
    fun `score au seuil ou non vehicule - ignores`() {
        val approach = VehicleApproach()
        val weak = Detection("car", 0.35f, box(100f), width = 640, height = 480)
        val person = Detection("person", 0.9f, box(100f), width = 640, height = 480)
        approach.update(listOf(weak, person), atMs = 0L)
        approach.update(listOf(weak, person), atMs = 100L)
        val third = approach.update(listOf(weak, person), atMs = 200L)

        assertFalse(third.approaching)
        assertEquals(0, approach.trackCount)
    }

    @Test
    fun `reset - oublie tout`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.reset()

        assertEquals(0, approach.trackCount)
        assertFalse(approach.update(listOf(car(200f)), atMs = 100L).approaching)
    }

    @Test
    fun `croissance lente sous le seuil - pas de veto`() {
        // V1 (signal fond absent) : sans fond, seule une croissance franche parle.
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.update(listOf(car(100.25f)), atMs = 100L)
        val third = approach.update(listOf(car(100.5f)), atMs = 200L)

        assertFalse("2,5 px/s lissés sous 3,2 px/s : bruit", third.approaching)
        assertNull(third.ttcSeconds)
        assertEquals("piste suivie mais muette", 1, approach.trackCount)
    }

    @Test
    fun `glissade laterale sans croissance - pas de veto approche`() {
        // V1 (signal fond absent) : la boîte qui glisse à largeur constante
        // n'est pas une approche — sur fond immobile, ce sera une caméra.
        val approach = VehicleApproach()
        approach.update(listOf(carAt(100f, 300f)), atMs = 0L)
        approach.update(listOf(carAt(100f, 320f)), atMs = 100L)
        val third = approach.update(listOf(carAt(100f, 340f)), atMs = 200L)

        assertFalse("latéral franc mais largeur stable", third.approaching)
        assertNull(third.ttcSeconds)
        assertNull(third.label)
        assertEquals(1, approach.trackCount)
    }

    @Test
    fun `camera qui suit - l approche se tait quand la boite suit le fond`() {
        // Fond qui défile à la vitesse de la boîte (50 px/s) : résidu nul,
        // la piste accuse la caméra et l'approche se tait.
        val motion = lateralMotion(dxPx = 0.75f)
        val approach = VehicleApproach()
        var last = ApproachVerdict(false, null, null)
        repeat(13) { index ->
            last = approach.update(listOf(driftingCar(index)), atMs = index * 100L, motion = motion)
        }

        assertFalse("piste embarquée par la caméra : pas d'approche", last.approaching)
        assertNull(last.ttcSeconds)
        assertTrue(approach.cameraMoved)
        assertEquals(Verdict.CAMERA_MOVED, approach.lastVerdicts.values.single().verdict)
    }

    @Test
    fun `camera qui suit mais voiture qui roule - le veto tient piste par piste`() {
        // Même défilement, mais la voiture centrée grandit sans dériver :
        // résidu franc, la piste roule et le veto tient malgré la caméra.
        val motion = lateralMotion(dxPx = 0.75f)
        val approach = VehicleApproach()
        var last = ApproachVerdict(false, null, null)
        repeat(13) { index ->
            val detections = listOf(car(100f + index * 10f), driftingBus(index))
            last = approach.update(detections, atMs = index * 100L, motion = motion)
        }

        assertTrue("la voiture qui roule n'est pas tue par la caméra", last.approaching)
        assertEquals("car", last.label)
        assertFalse("unanimité rompue : pas de veto global", approach.cameraMoved)
        assertEquals(Verdict.VEHICLE_MOVED, approach.lastVerdicts[0]?.verdict)
        assertEquals(Verdict.CAMERA_MOVED, approach.lastVerdicts[1]?.verdict)
    }

    @Test
    fun `voiture qui traverse le champ - lateral des la 3e frame, pas d approche`() {
        val approach = VehicleApproach()
        assertFalse(approach.update(listOf(carAt(100f, 200f)), atMs = 0L).lateralMoving)
        assertFalse(approach.update(listOf(carAt(100f, 230f)), atMs = 100L).lateralMoving)
        val third = approach.update(listOf(carAt(100f, 260f)), atMs = 200L)

        assertTrue("47 %/s lissés > 8 %/s", third.lateralMoving)
        assertEquals("car", third.lateralLabel)
        assertFalse("taille stable : pas d'approche", third.approaching)
        assertNull(third.ttcSeconds)
    }

    @Test
    fun `dt 500ms - association tenue et veto a la 6e frame`() {
        val approach = VehicleApproach()
        listOf(60f, 90f, 135f, 203f, 304f).forEachIndexed { index, width ->
            assertFalse("run ${index + 1} < 5 : le tau attend",
                approach.update(listOf(car(width)), atMs = index * 500L).approaching)
        }
        val sixth = approach.update(listOf(car(456f)), atMs = 2500L)

        assertTrue("IoU > 0,15 : piste tenue", sixth.approaching)
        assertNotNull(sixth.ttcSeconds)
        assertTrue("tau ~2,7 s", sixth.ttcSeconds!! in 1.0f..3.0f)
        assertEquals("car", sixth.label)
        assertEquals(1, approach.trackCount)
    }

    @Test
    fun `dt 900ms - association tenue sans veto fantome`() {
        val approach = VehicleApproach()
        approach.update(listOf(car(100f)), atMs = 0L)
        approach.update(listOf(car(150f)), atMs = 900L)
        val third = approach.update(listOf(car(200f)), atMs = 1800L)

        assertEquals("piste unique tenue à 900 ms", 1, approach.trackCount)
        assertFalse("croissance lente : pas de veto", third.approaching)
        assertNull(third.ttcSeconds)
    }

    @Test
    fun `derive lente - pas de lateral`() {
        val approach = VehicleApproach()
        approach.update(listOf(carAt(100f, 320f)), atMs = 0L)
        approach.update(listOf(carAt(100f, 322f)), atMs = 100L)
        val third = approach.update(listOf(carAt(100f, 324f)), atMs = 200L)

        assertFalse("3 %/s : tremblé, pas traversée", third.lateralMoving)
        assertNull(third.lateralLabel)
    }

    @Test
    fun `approche frontale - lateral muet`() {
        val approach = VehicleApproach()
        listOf(100f, 110f, 120f, 130f, 140f).forEachIndexed { index, width ->
            approach.update(listOf(car(width)), atMs = index * 100L)
        }
        val sixth = approach.update(listOf(car(150f)), atMs = 500L)

        assertTrue(sixth.approaching)
        assertFalse("vitesse x nulle", sixth.lateralMoving)
    }

    @Test
    fun `camera qui panoramique - le lateral se tait quand la boite suit le fond`() {
        // Même glissade franche que la traversée (+30 px/frame), mais le fond
        // défile pareil : résidu nul, la piste accuse la caméra.
        val motion = lateralMotion(dxPx = 4.5f)
        val approach = VehicleApproach()
        var last = ApproachVerdict(false, null, null)
        repeat(13) { index ->
            last = approach.update(listOf(carAt(100f, 100f + index * 30f)), atMs = index * 100L, motion = motion)
        }

        assertFalse("piste embarquée par la caméra : pas de latéral", last.lateralMoving)
        assertNull(last.lateralLabel)
        assertTrue(approach.cameraMoved)
    }

    @Test
    fun `purge 1500ms - trou 1200ms tenu, trou 1600ms purge`() {
        val kept = VehicleApproach()
        kept.update(listOf(car(100f)), atMs = 0L)
        kept.update(listOf(car(110f)), atMs = 500L)
        kept.update(listOf(carFar()), atMs = 1700L)
        assertEquals("1200 ms < 1500 : ancienne piste tenue", 2, kept.trackCount)

        val purged = VehicleApproach()
        purged.update(listOf(car(100f)), atMs = 0L)
        purged.update(listOf(car(110f)), atMs = 500L)
        purged.update(listOf(carFar()), atMs = 2100L)
        assertEquals("1600 ms > 1500 : ancienne piste purgée", 1, purged.trackCount)
    }

    @Test
    fun `reactivation courte - retour rapide garde la piste, retour tardif non`() {
        val static = FrameMotion(MotionSample(0f, 0f, 1f, 0.9f), 96, 96, 500L)
        val quick = VehicleApproach()
        quick.update(listOf(car(100f)), atMs = 0L)
        quick.update(listOf(car(200f)), atMs = 500L)
        quick.update(listOf(car(300f)), atMs = 1000L)
        quick.update(listOf(carFar()), atMs = 2600L)
        quick.update(listOf(car(350f)), atMs = 2800L, motion = static)
        assertEquals("200 ms < 500 : piste réactivée, verdict fond", 1, quick.lastVerdicts.size)

        val late = VehicleApproach()
        late.update(listOf(car(100f)), atMs = 0L)
        late.update(listOf(car(200f)), atMs = 500L)
        late.update(listOf(car(300f)), atMs = 1000L)
        late.update(listOf(carFar()), atMs = 2600L)
        late.update(listOf(car(350f)), atMs = 3300L, motion = static)
        assertTrue("700 ms > 500 : nouvelle piste sans vitesse", late.lastVerdicts.isEmpty())
    }

    @Test
    fun `glissade laterale 500ms - TTC lateral expose sans veto`() {
        val approach = VehicleApproach()
        approach.update(listOf(carAt(100f, 320f)), atMs = 0L)
        approach.update(listOf(carAt(100f, 380f)), atMs = 500L)
        val third = approach.update(listOf(carAt(100f, 440f)), atMs = 1000L)

        assertFalse("latéral sans croissance : pas d'approche", third.approaching)
        assertNull(third.ttcSeconds)
        assertNotNull("TTC latéral exposé au veto", third.lateralTtcSeconds)
        assertTrue("tau latéral ~1,6 s", third.lateralTtcSeconds!! in 0.5f..3.0f)
    }

    @Test
    fun `vitesse radiale - approche confirmee sans croissance, recession n efface pas le tau`() {
        val key = VehicleApproach.instanceKeyOf("car", 0.5f)
        val aided = VehicleApproach()
        aided.update(listOf(car(100f)), atMs = 0L)
        aided.update(listOf(car(102f)), atMs = 500L)
        val confirmed = aided.update(listOf(car(104f)), atMs = 1000L, radialMpsByKey = mapOf(key to -2f))
        assertTrue("radiale -2 m/s : approche", confirmed.approaching)
        assertEquals("car", confirmed.label)

        val bare = VehicleApproach()
        bare.update(listOf(car(100f)), atMs = 0L)
        bare.update(listOf(car(102f)), atMs = 500L)
        val silent = bare.update(listOf(car(104f)), atMs = 1000L)
        assertFalse("sans radiale : croissance lente muette", silent.approaching)

        val noisy = VehicleApproach()
        listOf(60f, 90f, 135f, 203f, 304f).forEachIndexed { index, width ->
            noisy.update(listOf(car(width)), atMs = index * 500L, radialMpsByKey = mapOf(key to 5f))
        }
        val held = noisy.update(listOf(car(456f)), atMs = 2500L, radialMpsByKey = mapOf(key to 5f))
        assertTrue("tau franc : récession n'efface pas", held.approaching)
        assertNotNull(held.ttcSeconds)
    }

    @Test
    fun `cles par instance - hush lateralise a 500ms`() {
        val motion = lateralMotion(dxPx = 1.5f, dtMs = 500L)
        val approach = VehicleApproach()
        var last = ApproachVerdict(false, null, null)
        repeat(13) { index ->
            val left = carAt(100f, 60f + index * 10f)
            val right = carAt(100f + index * 20f, 500f)
            last = approach.update(listOf(left, right), atMs = index * 500L, motion = motion)
        }

        assertFalse("croissance lente : pas d'approche", last.approaching)
        assertFalse("unanimité rompue", approach.cameraMoved)
        assertEquals(setOf("car@L"), approach.cameraMovedKeys)
        assertEquals("car@L", VehicleApproach.instanceKeyOf("car", 60f / 640f))
        assertEquals("car@R", VehicleApproach.instanceKeyOf("Cars", 500f / 640f))
    }

    /** Mouvement global latéral piloté : [dxPx] px par frame sur plan 96 ([dtMs]). */
    private fun lateralMotion(dxPx: Float, dtMs: Long = 100L): FrameMotion =
        FrameMotion(MotionSample(dxPx, 0f, 1f, 0.9f), lumaWidth = 96, lumaHeight = 96, dtMs = dtMs)

    /** Voiture qui grandit (+10 px/frame) en dérivant (+5 px/frame). */
    private fun driftingCar(index: Int): Detection = carAt(100f + index * 10f, 320f + index * 5f)

    /** Bus à largeur constante qui dérive comme le fond (+5 px/frame). */
    private fun driftingBus(index: Int): Detection {
        val cx = 500f + index * 5f
        return Detection("bus", 0.9f, listOf(cx - 50f, 300f, cx + 50f, 360f), width = 640, height = 480)
    }

    /** Voiture centrée de [widthPx] de large (ratio voiture 5:3). */
    private fun car(widthPx: Float): Detection =
        Detection("car", 0.9f, box(widthPx), width = 640, height = 480)

    /** Voiture de [widthPx] de large centrée en [cx] (glissade à largeur constante). */
    private fun carAt(widthPx: Float, cx: Float): Detection {
        val heightPx = widthPx * 0.6f
        return Detection(
            "car",
            0.9f,
            listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f),
            width = 640,
            height = 480,
        )
    }

    /** Bus statique à droite, loin de la voiture (IoU nulle entre les deux). */
    private fun busStatic(): Detection =
        Detection("bus", 0.9f, listOf(500f, 300f, 600f, 360f), width = 640, height = 480)

    /** Voiture ailleurs dans l'image (autre piste que [car]). */
    private fun carFar(): Detection =
        Detection("car", 0.9f, listOf(10f, 10f, 110f, 70f), width = 640, height = 480)

    /** Toute petite boîte centrée (bruit lointain). */
    private fun tiny(widthPx: Float): Detection =
        Detection("car", 0.9f, box(widthPx), width = 640, height = 480)

    private fun box(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `saut de piste - run remis a zero et tau desarme (coupe de scene)`() {
        val track = armedTrack()
        // Nouvel objet large ailleurs (croissance brute mais saut) : pas de tau sans run.
        val jumped = Box.fromProto(listOf(10f, 10f, 210f, 130f))!!
        feedTrackRun(track, jumped, 1200L, maxSpeedPx())
        assertEquals("saut : nouvel objet", 0, track.growthRun)
        assertFalse("l'armement S2 meurt au saut", track.tauArmed)
        assertFalse("pas de tau sans run", tauEligible(track, 640, 200f, null, 0L))
    }

    @Test
    fun `frame sans date - run remis a zero, armement conserve (anomalie horloge)`() {
        val track = armedTrack()
        feedTrackRun(track, centeredBox(150f), 1000L, maxSpeedPx())
        assertEquals("STALE : run cassé", 0, track.growthRun)
        assertTrue("STALE n'est pas un nouvel objet", track.tauArmed)
        assertTrue("le tau tient sur vélocité", tauEligible(track, 640, 150f, null, 0L))
    }

    /** Piste armée : 5 croissances brutes puis éligibilité constatée (run-5). */
    private fun armedTrack(): VehicleApproach.Track {
        val track = VehicleApproach.Track("car", MotionModel(centeredBox(100f), 0L), 0L)
        listOf(110f, 120f, 130f, 140f, 150f).forEachIndexed { index, width ->
            feedTrackRun(track, centeredBox(width), (index + 1) * 200L, maxSpeedPx())
        }
        assertEquals(5, track.growthRun)
        assertTrue("run-5 + vélocité : armé", tauEligible(track, 640, 150f, null, 0L))
        return track
    }

    /** Vitesse max piste (miroir de l'association). */
    private fun maxSpeedPx(): Float = 640f * BoxPredictor.DEFAULT_MAX_SPEED_RATIO

    /** Boîte centrée 640x480 (miroir de [box], objet [Box]). */
    private fun centeredBox(widthPx: Float): Box = Box.fromProto(box(widthPx))!!
}
