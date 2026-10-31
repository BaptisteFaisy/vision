package dev.sam3vision.core.distance

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sténopé, lissage par piste et bornes de plausibilité. */
class DistanceMeterTest {

    private fun detection(label: String, box: List<Float>): Detection = Detection(label, 0.9f, box, 512, 384)

    /** Boîte de hauteur [heightPx] dont le haut est à 100 px. */
    private fun box(heightPx: Float): List<Float> = listOf(100f, 100f, 200f, 100f + heightPx)

    @Test
    fun `personne de 200 px a 3 3 m avec la focale par defaut`() {
        val focal = LensSpec.Default.focalPx(512, 384)
        val meters = Pinhole.distanceMeters(box(200f), 1.70f, focal)!!
        assertEquals(3.29f, meters, 0.02f)
    }

    @Test
    fun `deux fois plus petite dans l'image, deux fois plus loin`() {
        val focal = LensSpec.Default.focalPx(512, 384)
        val near = Pinhole.distanceMeters(box(200f), 1.70f, focal)!!
        val far = Pinhole.distanceMeters(box(100f), 1.70f, focal)!!
        assertEquals(2f, far / near, 0.01f)
    }

    @Test
    fun `boite inexploitable ou hors bornes donne null`() {
        val focal = LensSpec.Default.focalPx(512, 384)
        assertNull(Pinhole.distanceMeters(box(1f), 1.70f, focal))
        assertNull(Pinhole.distanceMeters(listOf(1f, 2f), 1.70f, focal))
        assertNull(Pinhole.distanceMeters(box(200f), 0f, focal))
        assertNull(Pinhole.distanceMeters(box(200f), 1.70f, 0f))
        // boîte immense : l'objet serait à 13 cm, sous le plancher de plausibilité
        assertNull(Pinhole.distanceMeters(box(5_000f), 1.70f, focal))
    }

    @Test
    fun `objet coupe par le bord haut ou bas n'a pas de distance`() {
        val meter = DistanceMeter()
        // collé au bord haut, puis au bord bas : la hauteur ne mesure plus l'objet
        assertTrue(meter.measure(512, 384, listOf(detection("personne", listOf(100f, 0f, 200f, 200f)))).isEmpty())
        assertTrue(meter.measure(512, 384, listOf(detection("personne", listOf(100f, 200f, 200f, 384f)))).isEmpty())
        // coupé à gauche : la hauteur reste fiable, la mesure tient
        assertEquals(1, meter.measure(512, 384, listOf(detection("personne", listOf(0f, 100f, 200f, 300f)))).size)
        // marge de tolérance (2 px) : plus bas que ça, la boîte n'est pas coupée
        assertEquals(1, meter.measure(512, 384, listOf(detection("personne", listOf(100f, 100f, 200f, 380f)))).size)
        assertTrue(Pinhole.isVerticallyCut(listOf(100f, 100f, 200f, 382f), 384))
        assertTrue(!Pinhole.isVerticallyCut(listOf(100f, 100f, 200f, 380f), 384))
    }

    @Test
    fun `boite collee aux deux bords est ecartee`() {
        val meter = DistanceMeter()
        val fullHeight = listOf(0f, 0f, 512f, 384f)
        assertTrue(Pinhole.isVerticallyCut(fullHeight, 384))
        assertTrue(meter.measure(512, 384, listOf(detection("personne", fullHeight))).isEmpty())
    }

    @Test
    fun `meter rend une lecture par objet calibre`() {
        val meter = DistanceMeter()
        val readings = meter.measure(
            width = 512,
            height = 384,
            detections = listOf(detection("personne", box(200f)), detection("licorne", box(150f))),
            trackIds = listOf(7, 8),
        )
        assertEquals(1, readings.size)
        val person = readings.first()
        assertEquals("personne", person.label)
        assertEquals(7, person.trackId)
        assertEquals(3.29f, person.meters, 0.02f)
        assertEquals(person.rawMeters, person.meters, 0.001f)
        assertEquals(4, person.box.size)
    }

    @Test
    fun `sans calibrage ou desactive, aucune lecture`() {
        val meter = DistanceMeter()
        meter.enabled = false
        assertTrue(meter.measure(512, 384, listOf(detection("personne", box(200f)))).isEmpty())

        val broken = DistanceMeter(LensSpec(0f, 0f))
        assertTrue(broken.measure(512, 384, listOf(detection("personne", box(200f)))).isEmpty())
    }

    @Test
    fun `calibrage inutilisable n'ecrase pas le precedent`() {
        val meter = DistanceMeter(LensSpec(70f, 55f))
        meter.setLens(LensSpec(0f, 0f))
        assertEquals(70f, meter.lensSpec.horizontalFovDeg, 0.01f)
        meter.setLens(null)
        assertEquals(70f, meter.lensSpec.horizontalFovDeg, 0.01f)
    }

    @Test
    fun `piste lisse la mesure, objet non identifie passe brut`() {
        val smoother = DistanceSmoother()
        val meter = DistanceMeter(LensSpec.Default, smoother)
        val first = meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(3)).first()
        val second = meter.measure(512, 384, listOf(detection("personne", box(180f))), listOf(3)).first()
        assertTrue(second.rawMeters > first.rawMeters)
        assertTrue(second.meters < second.rawMeters)
        assertTrue(second.meters > first.meters)

        val untracked = meter.measure(512, 384, listOf(detection("personne", box(180f))), listOf(null)).first()
        assertEquals(DistanceMeter.UNTRACKED_ID, untracked.trackId)
        assertEquals(untracked.rawMeters, untracked.meters, 0.001f)
    }

    @Test
    fun `lissage suit un rapprochement doux et saute sur un bond`() {
        val smoother = DistanceSmoother()
        val start = smoother.smooth(1, 4.0f, 0L)
        val gentle = smoother.smooth(1, 4.4f, 100L)
        assertEquals(4.0f, start, 0.001f)
        assertTrue(gentle in 4.0f..4.4f)
        assertTrue("un pas doux reste prudent", gentle < 4.25f)

        val jump = smoother.smooth(1, 1.0f, 200L)
        assertTrue("un bond est pris presque tel quel", jump < 2.0f)
        assertEquals(1, smoother.size())
    }

    @Test
    fun `piste muette est oubliee et repart de la mesure brute`() {
        val smoother = DistanceSmoother(timeoutMs = 1_000L)
        smoother.smooth(1, 4.0f, 0L)
        val back = smoother.smooth(1, 9.0f, 5_000L)
        assertEquals(9.0f, back, 0.001f)
        smoother.reset()
        assertEquals(0, smoother.size())
    }

    @Test
    fun `mesure reelle tient dans les bornes annoncees`() {
        val meter = DistanceMeter()
        val reading = meter.measure(384, 512, listOf(detection("personne", box(150f)))).firstOrNull()
        assertNotNull(reading)
        assertTrue(reading!!.meters in Pinhole.MIN_METERS..Pinhole.MAX_METERS)
    }

    @Test
    fun `boite masquee par une meilleure detection est ecartee`() {
        val meter = DistanceMeter()
        val car = Detection("voiture", 0.95f, listOf(50f, 50f, 450f, 350f), 512, 384)
        val hidden = Detection("personne", 0.60f, listOf(100f, 100f, 200f, 300f), 512, 384)
        val readings = meter.measure(512, 384, listOf(car, hidden))
        assertEquals(1, readings.size)
        assertEquals("voiture", readings.first().label)
    }

    @Test
    fun `recouvrement partiel ou scores egaux, les deux sont mesures`() {
        val meter = DistanceMeter()
        val left = Detection("personne", 0.90f, listOf(50f, 100f, 200f, 300f), 512, 384)
        val right = Detection("personne", 0.90f, listOf(150f, 100f, 300f, 300f), 512, 384)
        assertEquals(2, meter.measure(512, 384, listOf(left, right)).size)
        // contenue à 50 % seulement : gardée malgré le score inférieur
        val big = Detection("voiture", 0.95f, listOf(50f, 50f, 250f, 350f), 512, 384)
        val half = Detection("personne", 0.60f, listOf(150f, 100f, 350f, 300f), 512, 384)
        assertEquals(2, meter.measure(512, 384, listOf(big, half)).size)
    }

    @Test
    fun `confiance entre 0 et 1, croissante avec la hauteur de boite`() {
        val meter = DistanceMeter()
        val small = meter.measure(512, 384, listOf(detection("personne", box(20f)))).first()
        val tall = meter.measure(512, 384, listOf(detection("personne", box(200f)))).first()
        assertTrue(small.confidence in 0f..1f)
        assertTrue(tall.confidence in 0f..1f)
        assertTrue("une grande boîte vaut mieux qu'une petite", tall.confidence > small.confidence)
        assertEquals(1f - 1f / 200f, tall.confidence, 0.001f)
    }

    @Test
    fun `bbox du masque mesure a la place de la boite detecteur`() {
        val meter = DistanceMeter()
        val masked = detection("personne", box(200f)).copy(maskBox = listOf(110f, 120f, 190f, 290f))
        val reading = meter.measure(512, 384, listOf(masked)).first()
        val focal = LensSpec.Default.focalPx(512, 384)
        assertEquals(1.70f * focal / 170f, reading.rawMeters, 0.001f)
        assertEquals(box(200f), reading.box)
    }

    @Test
    fun `bbox du masque coupee ecarte la mesure`() {
        val meter = DistanceMeter()
        val cut = detection("personne", box(200f)).copy(maskBox = listOf(110f, 0f, 190f, 290f))
        assertTrue(meter.measure(512, 384, listOf(cut)).isEmpty())
    }

    @Test
    fun `repli EMA quand le Kalman est coupe`() {
        val meter = DistanceMeter().apply { kalmanEnabled = false }
        val reference = DistanceSmoother()
        val first = meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(3)).first()
        val second = meter.measure(512, 384, listOf(detection("personne", box(180f))), listOf(3)).first()
        reference.smooth(3, first.rawMeters)
        assertEquals(reference.smooth(3, second.rawMeters), second.meters, 0f)
        assertTrue(meter.predictions().isEmpty())
    }

    @Test
    fun `reset oublie aussi la piste Kalman`() {
        val meter = DistanceMeter()
        meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(3))
        meter.reset()
        val fresh = meter.measure(512, 384, listOf(detection("personne", box(100f))), listOf(3)).first()
        assertEquals(fresh.rawMeters, fresh.meters, 0f)
        assertTrue(meter.predictions().isNotEmpty())
    }

    @Test
    fun `predictions suit la piste puis oublie`() {
        val meter = DistanceMeter()
        val t0 = System.currentTimeMillis()
        val measured = meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(7)).first()
        val predicted = meter.predictions(t0 + 500L)
        assertEquals(1, predicted.size)
        assertEquals(7, predicted.first().trackId)
        assertEquals("personne", predicted.first().label)
        assertEquals(measured.rawMeters, predicted.first().meters, 0.001f)
        assertTrue(predicted.first().confidence < measured.confidence)
        assertTrue(meter.predictions(t0 + 5_000L).isEmpty())
    }

    @Test
    fun `predictions maintient le non-suivi puis oublie`() {
        val meter = DistanceMeter()
        val t0 = System.currentTimeMillis()
        val measured = meter.measure(512, 384, listOf(detection("personne", box(200f)))).first()
        val held = meter.predictions(t0 + 500L)
        assertEquals(1, held.size)
        assertEquals(DistanceMeter.UNTRACKED_ID, held.first().trackId)
        assertEquals(measured.meters, held.first().meters, 0f)
        assertTrue(meter.predictions(t0 + 5_000L).isEmpty())
    }

    @Test
    fun `predictions vides sans mesure ou mesures coupees`() {
        assertTrue(DistanceMeter().predictions(1_000L).isEmpty())
        val meter = DistanceMeter().apply { enabled = false }
        meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(7))
        assertTrue(meter.predictions(2_000L).isEmpty())
    }

    @Test
    fun `spec mesuree redresse la boite avant de mesurer`() {
        val spec = LensSpec.fromMeasured(4.3f, 5.76f, 4.32f, 4000, 3000)!!
        val plain = DistanceMeter(spec)
            .measure(512, 384, listOf(detection("personne", box(200f)))).first()
        // distorsion nulle : la mesure est au bit près celle sans distorsion
        val zero = DistanceMeter(spec.copy(distortion = RadialDistortion(0f, 0f)))
            .measure(512, 384, listOf(detection("personne", box(200f)))).first()
        assertEquals(plain.rawMeters, zero.rawMeters, 0f)
        // barillet : la hauteur redressée (centre x = 150 px) porte la mesure
        val distortion = RadialDistortion(-0.1f)
        val corrected = DistanceMeter(spec.copy(distortion = distortion))
            .measure(512, 384, listOf(detection("personne", box(200f)))).first()
        val focal = spec.focalPx(512, 384)
        val (cx, cy) = spec.principalPx(512, 384)
        val norm = Distortion.normPx(512, 384)
        val top = Distortion.undistortY(150f, 100f, cx, cy, norm, distortion)
        val bottom = Distortion.undistortY(150f, 300f, cx, cy, norm, distortion)
        assertEquals(1.70f * focal / (bottom - top), corrected.rawMeters, 0.001f)
        assertTrue("une boîte redressée plus petite éloigne", corrected.rawMeters > plain.rawMeters)
        assertEquals(box(200f), corrected.box)
    }

    @Test
    fun `coupe bas sans pose rend la derniere piste, signalee`() {
        val meter = DistanceMeter()
        val first = meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(3)).first()
        val cut = listOf(100f, 200f, 200f, 384f)
        val fallback = meter.measure(512, 384, listOf(detection("personne", cut)), listOf(3)).single()
        assertTrue(fallback.cutFallback)
        assertEquals(first.meters, fallback.meters, 0.02f)
        assertEquals(fallback.meters, fallback.rawMeters, 0f)
        assertTrue(fallback.confidence in 0f..DistanceMeter.CUT_FALLBACK_MAX_CONFIDENCE)
    }

    @Test
    fun `coupe haut refuse meme avec piste et pose`() {
        val meter = DistanceMeter()
        meter.pose = CameraPose(1.5f, 0.2f)
        meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(3))
        val cut = listOf(100f, 0f, 200f, 200f)
        assertTrue(meter.measure(512, 384, listOf(detection("personne", cut)), listOf(3)).isEmpty())
    }

    @Test
    fun `repli ne nourrit pas la piste Kalman`() {
        val meter = DistanceMeter()
        val t0 = System.currentTimeMillis()
        val cut = listOf(100f, 200f, 200f, 384f)
        meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(3))
        val fallback = meter.measure(512, 384, listOf(detection("personne", cut)), listOf(3)).single()
        assertTrue(fallback.cutFallback)
        val predicted = meter.predictions(t0 + 500L).first { it.trackId == 3 }
        assertEquals(box(200f), predicted.box)
    }

    @Test
    fun `repli non suivi se maintient drapeau conserve`() {
        val meter = DistanceMeter()
        meter.pose = CameraPose(1.5f, 0.2f)
        val t0 = System.currentTimeMillis()
        val cut = listOf(100f, 200f, 200f, 384f)
        val fallback = meter.measure(512, 384, listOf(detection("personne", cut))).single()
        assertTrue(fallback.cutFallback)
        val held = meter.predictions(t0 + 500L).single()
        assertTrue(held.cutFallback)
        assertEquals(fallback.meters, held.meters, 0f)
    }

    @Test
    fun `coupe bas en EMA rend le dernier lisse`() {
        val meter = DistanceMeter().apply { kalmanEnabled = false }
        meter.measure(512, 384, listOf(detection("personne", box(200f))), listOf(3))
        val cut = listOf(100f, 200f, 200f, 384f)
        val fallback = meter.measure(512, 384, listOf(detection("personne", cut)), listOf(3)).single()
        assertTrue(fallback.cutFallback)
        assertEquals(3.29f, fallback.meters, 0.02f)
    }

    @Test
    fun `dernier lisse relu sans modifier, perime oublie`() {
        val smoother = DistanceSmoother(timeoutMs = 1_000L)
        assertNull(smoother.last(1, 0L))
        smoother.smooth(1, 4.0f, 0L)
        assertEquals(4.0f, smoother.last(1, 500L)!!, 0f)
        assertNull(smoother.last(1, 5_000L))
        // relire ne prolonge pas : l'oubli reste à 1 s après la mesure
        assertNull(smoother.last(1, 1_001L))
        assertEquals(1, smoother.size())
    }
}
