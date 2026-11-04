package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingPrompts
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
 * Verdict de bout en bout sur boîtes SAM réelles (GPU `sam3-eu-a100`, prompts
 * de la Traversée, frames 180×320 q55, 04/11/2026) rejouées dans le vrai veto
 * et le vrai moteur : le fond y reste muet, comme `BlockMotionSource` sur ces
 * vidéos. Des voitures garées filmées à la main disent « Cross. », celles qui
 * roulent « Don't cross. ». Les vidéos sont celles du Samsung de test.
 *
 * Le feu ne se lit pas ici (pas de pixels) : la boîte d'un panneau sans lampe
 * s'écarte comme dans `crossingLightOf`, les crops réels le prouvent dans
 * `SignLightVetoTest`. [replay] avec `withoutLight` retire les vrais feux des
 * vidéos témoin : c'est la branche sans feu, celle où les voitures tranchent.
 */
class CrossingParkedVideoTest {

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

    private fun lightOf(frame: LiveFrame): CrossingLight {
        val signs = CrossingPrompts.signLikeBoxes(frame.objects, frame.width, frame.height)
        val box = CrossingPrompts.selectLightBoxes(frame.objects, frame.width, frame.height).firstOrNull { it !in signs }
        return CrossingLight(box, CrossingState.Red)
    }

    private fun readingsOf(frame: LiveFrame, focalPx: Float): List<DistanceReading> =
        frame.objects.mapNotNull { item ->
            val height = KnownSizes.heightMeters(item.label) ?: return@mapNotNull null
            val meters = Pinhole.distanceMeters(item.box, height, focalPx) ?: return@mapNotNull null
            DistanceReading(-1, item.label, meters, meters, item.box)
        }

    /** Rejoue [name] dans le veto puis le moteur, dans l'ordre de `computeRest`. */
    private fun replay(name: String, withoutLight: Boolean = false): List<Step> {
        val veto = TraverseVeto()
        val engine = CrossingVerdictEngine(
            readLight = { frame, _ -> if (withoutLight) CrossingLight(null, CrossingState.Red) else lightOf(frame) },
            poster = CrossingVerdictPoster { _, _, _ -> true },
        )
        return load(name).map { timed ->
            val frame = timed.frame
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

    private fun List<Step>.spoken(): List<String> = mapNotNull { it.said }

    private fun List<Step>.between(fromMs: Long, toMs: Long): List<Step> = filter { it.atMs in fromMs..toMs }

    /** Dates des frames dont le verdict n'est pas [verdict] : vide = tenu partout. */
    private fun List<Step>.notSaying(verdict: CrossingVerdict): List<Long> = filter { it.verdict != verdict }.map { it.atMs }

    @Test
    fun `vans gares sur passage sans feu - Cross et rien d autre`() {
        // L'avant-dernière vidéo du Samsung : « Don't cross. » 44 frames sur 44 avant.
        val steps = replay("samsung-vans-gares.json")
        assertEquals(listOf("Cross."), steps.spoken().distinct())
        assertTrue("redit toutes les 2 s", steps.spoken().size >= 4)
        assertEquals(emptyList<Long>(), steps.between(600L, Long.MAX_VALUE).notSaying(CrossingVerdict.Cross))
        assertFalse("une garée ne dit jamais mouvement", steps.any { it.moving })
    }

    @Test
    fun `vans gares, seconde passe SAM a 7 ips - Cross tenu`() {
        val steps = replay("samsung-vans-gares-7ips.json")
        assertEquals(emptyList<Long>(), steps.between(800L, Long.MAX_VALUE).notSaying(CrossingVerdict.Cross))
        assertFalse(steps.any { it.moving })
    }

    @Test
    fun `van gare derriere une haie - jamais de mouvement, Cross majoritaire`() {
        // Reste connu : un van qui entre dans le cadre et des morceaux coupent
        // « Cross. » quelques frames ; zéro frame de « Cross. » avant le correctif.
        val steps = replay("samsung-van-gare-haie.json")
        assertFalse(steps.any { it.moving })
        val crossing = steps.count { it.verdict == CrossingVerdict.Cross }
        assertTrue("$crossing frames sur ${steps.size}", crossing * 4 >= steps.size * 3)
    }

    @Test
    fun `voiture qui passe devant des garees - Don't cross jusqu a 2 s apres`() {
        // Voiture noire à 2 m, garées derrière : elle bloque dès son entrée.
        val steps = replay("samsung-voiture-passe.json", withoutLight = true)
        val passing = steps.between(1_000L, 2_400L)
        assertTrue(passing.isNotEmpty())
        assertEquals(emptyList<Long>(), passing.notSaying(CrossingVerdict.DontCross))
        assertTrue("le passage dit mouvement", steps.any { it.moving })
    }

    @Test
    fun `voiture qui traverse le champ au loin - Don't cross tant qu elle roule`() {
        // 0,6 image/s, boîte de 20 px : le cas le plus dur du témoin.
        val steps = replay("samsung-voiture-loin.json", withoutLight = true)
        val driving = steps.between(2_000L, 3_400L)
        assertTrue(driving.isNotEmpty())
        assertEquals(emptyList<Long>(), driving.notSaying(CrossingVerdict.DontCross))
        assertTrue("rue vide ensuite : Cross revient", steps.last().verdict == CrossingVerdict.Cross)
    }

    @Test
    fun `garee ecartee - sa boite ne nourrit plus le mouvement`() {
        val growing = listOf(100f, 107f, 114f, 121f).map { width ->
            LiveFrame(640, 480, 0L, listOf(SegObject("car", 0.9f, listOf(300f - width, 200f, 300f + width, 280f), "")))
        }
        val still = CrossingVerdictEngine(readLight = { _, _ -> CrossingLight(null, CrossingState.Red) })
        val seen = CrossingVerdictEngine(readLight = { _, _ -> CrossingLight(null, CrossingState.Red) })
        var parkedMoving = false
        var liveMoving = false
        growing.forEachIndexed { index, frame ->
            parkedMoving = parkedMoving || still.onFrame(frame, emptyList(), "", index * 200L, parked = { true }).carMoving
            liveMoving = liveMoving || seen.onFrame(frame, emptyList(), "", index * 200L).carMoving
        }
        assertFalse("garée : jamais de mouvement", parkedMoving)
        assertTrue("non prouvée : le mouvement se lit toujours", liveMoving)
    }
}
