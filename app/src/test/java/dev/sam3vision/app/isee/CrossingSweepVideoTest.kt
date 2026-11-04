package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingVerdict
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.traverse.TraverseVeto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Balayage du téléphone sur boîtes SAM réelles : la vidéo Samsung 20261104_101016
 * recadrée par une fenêtre mobile (0,10 et 0,20 image/s, aller-retour), repassée
 * sur `sam3-eu-a100` avec les 20 prompts de la Traversée. Le moteur reçoit le
 * mouvement lu sur les objets fixes, comme en production (`computeRest`).
 *
 * Ces tests jugent les voitures : sans feu, ce sont elles qui tranchent. Une
 * garée balayée dit « Cross. », une voiture qui roule « Don't cross. » dans les
 * deux sens du balayage.
 */
class CrossingSweepVideoTest {

    /** Objectif principal du Samsung : ~240 px de focale sur 320 px de côté long. */
    private val lens = LensSpec(67.8f, 41.1f, sensorLandscape = true)

    private class Timed(val atMs: Long, val frame: LiveFrame)

    private class Step(val atMs: Long, val verdict: CrossingVerdict, val moving: Boolean, val said: String?)

    private fun load(name: String): List<Timed> {
        val text = javaClass.classLoader.getResourceAsStream("real-motion/$name")
            ?.bufferedReader()?.readText() ?: error("fixture absente : $name")
        val root = org.json.JSONObject(text)
        val frames = root.getJSONArray("frames")
        return List(frames.length()) { index ->
            val frame = frames.getJSONObject(index)
            val objects = frame.getJSONArray("objects")
            val parsed = List(objects.length()) { position ->
                val item = objects.getJSONObject(position)
                val box = item.getJSONArray("box")
                SegObject(item.getString("label"), item.getDouble("score").toFloat(), List(4) { box.getDouble(it).toFloat() }, "")
            }
            Timed(frame.getLong("t"), LiveFrame(root.getInt("width"), root.getInt("height"), 0L, parsed))
        }
    }

    private fun readingsOf(frame: LiveFrame, focalPx: Float): List<DistanceReading> =
        frame.objects.mapNotNull { item ->
            val height = KnownSizes.heightMeters(item.label) ?: return@mapNotNull null
            val meters = Pinhole.distanceMeters(item.box, height, focalPx) ?: return@mapNotNull null
            DistanceReading(-1, item.label, meters, meters, item.box)
        }

    /** Rejoue [name] dans le veto puis le moteur, dans l'ordre de `computeRest`. */
    private fun replay(name: String, scene: (Long, LiveFrame) -> LiveFrame = { _, frame -> frame }): List<Step> {
        val veto = TraverseVeto()
        val engine = CrossingVerdictEngine(
            readLight = { _, _ -> CrossingLight(null, CrossingState.Red) },
            poster = CrossingVerdictPoster { _, _, _ -> true },
        )
        return load(name).map { timed ->
            val frame = scene(timed.atMs, timed.frame)
            val focalPx = lens.focalPx(frame.width, frame.height)
            val detections = frame.objects.toDetections(frame.width, frame.height)
            val decision = veto.update(false, detections, focalPx, timed.atMs)
            val out = engine.onFrame(
                frame, readingsOf(frame, focalPx), "", timed.atMs, motion = veto.cameraMotion,
                carVeto = decision.veto, parked = veto::isParked,
            )
            Step(timed.atMs, out.verdict, out.carMoving, out.posts.firstOrNull()?.text)
        }
    }

    private val fixed = "samsung-balayage-fixe.json"
    private val slow = "samsung-balayage-010.json"
    private val fast = "samsung-balayage-020.json"

    /** Décalage de la fenêtre de capture à [atMs] (image), en aller-retour de ±0,15. */
    private fun sweep(atMs: Long, speed: Float): Float {
        val phase = (atMs / 1000f * speed) % 0.6f
        return if (phase < 0.15f) phase else if (phase < 0.45f) 0.3f - phase else phase - 0.6f
    }

    /** Voiture posée à [x] (bord gauche, fraction d'image) sur [frame], coupée par le cadre. */
    private fun withCar(frame: LiveFrame, x: Float): LiveFrame {
        val width = frame.width.toFloat()
        val height = frame.height.toFloat()
        val left = (x * width).coerceIn(0f, width)
        val right = ((x + 0.25f) * width).coerceIn(0f, width)
        if (right - left < 2f) return frame
        val car = SegObject("car", 0.9f, listOf(left, 0.30f * height, right, 0.42f * height), "")
        return LiveFrame(frame.width, frame.height, 0L, frame.objects + car)
    }

    private fun List<Step>.settled(): List<Step> = filter { it.atMs >= 800L }

    private fun assertMostlyCross(name: String) {
        val steps = replay(name).settled()
        val crossing = steps.count { it.verdict == CrossingVerdict.Cross }
        assertTrue("$crossing frames sur ${steps.size}", crossing * 100 >= steps.size * 90)
        assertFalse("une garée balayée ne dit jamais mouvement", steps.any { it.moving })
    }

    @Test
    fun `vans gares, telephone a la main - Cross tenu`() = assertMostlyCross(fixed)

    @Test
    fun `vans gares, balayage lent - Cross tenu`() = assertMostlyCross(slow)

    @Test
    fun `vans gares, balayage franc - Cross tenu`() = assertMostlyCross(fast)

    @Test
    fun `voiture qui roule pendant le balayage - Don't cross, dans les deux sens`() {
        for ((name, camera) in listOf(slow to 0.10f, fast to 0.20f)) {
            for (speed in listOf(-0.4f, -0.2f, -0.1f, 0.1f, 0.2f, 0.4f)) {
                val steps = replay(name) { atMs, frame ->
                    if (atMs < 2_000L) frame else withCar(frame, 0.35f + speed * (atMs - 2_000L) / 1000f - sweep(atMs, camera))
                }
                val driving = steps.filter { it.atMs in 3_000L..4_000L }
                assertTrue(driving.isNotEmpty())
                assertTrue("$name à $speed", driving.all { it.verdict == CrossingVerdict.DontCross })
            }
        }
    }

    @Test
    fun `garee qui demarre en plein balayage - Don't cross en 1,2 s, comme camera posee`() {
        for ((name, camera) in listOf(fixed to 0f, slow to 0.10f, fast to 0.20f)) {
            val steps = replay(name) { atMs, frame ->
                val running = (atMs - 4_000L).coerceAtLeast(0L) / 1000f
                withCar(frame, 0.35f + 0.125f * running * running - sweep(atMs, camera))
            }
            val parked = steps.filter { it.atMs in 2_500L..3_900L }
            assertTrue("$name : garée", parked.all { it.verdict == CrossingVerdict.Cross })
            val first = steps.first { it.atMs >= 4_000L && it.verdict == CrossingVerdict.DontCross }
            assertTrue("$name : ${first.atMs}", first.atMs <= 5_200L)
        }
    }

    @Test
    fun `voiture qui passe, balayage lent - Don't cross tant qu elle est la`() {
        val steps = replay("samsung-voiture-passe.json") { atMs, frame ->
            val shift = sweep(atMs, 0.10f) * frame.width
            LiveFrame(frame.width, frame.height, 0L, frame.objects.map { item ->
                val box = item.box
                SegObject(item.label, item.score, listOf(box[0] + shift, box[1], box[2] + shift, box[3]).clamped(frame.width), "")
            })
        }
        val passing = steps.filter { it.atMs in 1_000L..2_200L }
        assertTrue(passing.isNotEmpty())
        assertTrue(passing.all { it.verdict == CrossingVerdict.DontCross })
    }

    private fun List<Float>.clamped(width: Int): List<Float> =
        listOf(this[0].coerceIn(0f, width.toFloat()), this[1], this[2].coerceIn(0f, width.toFloat()), this[3])
}
