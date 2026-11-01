package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voix véhicules traversée sur boîtes SAM 3.1 réelles (GPU `sam3-eu-a100`,
 * prompts et knobs de production, 2026-11-01) : le pan sur des taxis garés
 * (S1, 40 frames à 5 im/s) annonce l'arrêt, jamais de mouvement ; la dashcam
 * (S2, idem) annonce le mouvement sous tous les défilements plausibles. Les
 * boîtes portent le vrai jitter serveur (scores, va-et-vient, changement de
 * piste) que les boîtes synthétiques ne rendent pas.
 */
class CrossingRealMotionTest {

    private data class Frame(val detections: List<Detection>)

    /** Frames d'une fixture (`real-motion/*.json`), boîtes pixels 640×480. */
    private fun loadFrames(name: String): List<Frame> {
        val text = javaClass.classLoader.getResourceAsStream("real-motion/$name")
            ?.bufferedReader()?.readText() ?: error("fixture absente : $name")
        val frames = org.json.JSONArray(text)
        return List(frames.length()) { index ->
            val objects = frames.getJSONObject(index).getJSONArray("objects")
            Frame(List(objects.length()) { j ->
                val box = objects.getJSONObject(j).getJSONArray("box")
                Detection(
                    objects.getJSONObject(j).getString("label"),
                    objects.getJSONObject(j).getDouble("score").toFloat(),
                    listOf(box.getDouble(0).toFloat(), box.getDouble(1).toFloat(),
                        box.getDouble(2).toFloat(), box.getDouble(3).toFloat()),
                    width = 640, height = 480,
                )
            })
        }
    }

    private fun reading(meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = "car", meters = meters, rawMeters = meters, box = box)

    /** Pan S1 mesuré sur boîtes réelles : −26 px/s, fond franc, confiance haute. */
    private fun panMotion(): FrameMotion =
        FrameMotion(MotionSample(-0.78f, 0f, 1f, 0.9f), 96, 96, 200L)

    /** Défilements dashcam plausibles (+ repli sans signal) : le mouvement doit survivre à tous. */
    private fun egoMotions(): List<Pair<String, FrameMotion?>> = listOf(
        "sans-fond" to null,
        "route" to FrameMotion(MotionSample(0f, 2.4f, 1f, 0.8f), 96, 96, 200L),
        "route-rapide" to FrameMotion(MotionSample(0f, 4.8f, 1f, 0.8f), 96, 96, 200L),
        "lacet" to FrameMotion(MotionSample(1.5f, 0.5f, 1f, 0.8f), 96, 96, 200L),
    )

    @Test
    fun `s1 reel - pan sur garees annonce arret, jamais mouvement`() {
        CrossingVehicleWatch.reset()
        val motion = panMotion()
        var stopped = 0
        loadFrames("s1-pan.json").forEachIndexed { index, frame ->
            val box = frame.detections.first { it.label == "car" }.box
            val said = CrossingVehicleWatch.update(
                frame.detections, listOf(reading(5f, box)),
                index * 200L, SpeechLanguage.En, motion = motion,
            )
            if (said != null && "stopped" in said) stopped += 1
            assertFalse("jamais de stop sur des garées (frame $index : $said)",
                (said ?: "").startsWith("Stop"))
            assertFalse("jamais de mouvement sur des garées (frame $index : $said)",
                "moving" in (said ?: ""))
        }

        assertTrue("arrêt compensé annoncé au moins une fois", stopped >= 1)
        assertFalse(CrossingVehicleWatch.movingNow)
    }

    @Test
    fun `s1 reel sans fond - doute silencieux, pas d arret prouve`() {
        CrossingVehicleWatch.reset()
        loadFrames("s1-pan.json").forEachIndexed { index, frame ->
            val box = frame.detections.first { it.label == "car" }.box
            val said = CrossingVehicleWatch.update(
                frame.detections, listOf(reading(5f, box)),
                index * 200L, SpeechLanguage.En,
            )
            assertEquals("sans fond, la dérive reste un doute (frame $index)", null, said)
        }
    }

    @Test
    fun `s2 reel - mouvement annonce sous tous les defilements`() {
        val frames = loadFrames("s2-dash.json")
        for ((name, motion) in egoMotions()) {
            CrossingVehicleWatch.reset()
            var moving = 0
            frames.forEachIndexed { index, frame ->
                val box = frame.detections.first { it.label == "car" }.box
                val said = CrossingVehicleWatch.update(
                    frame.detections, listOf(reading(12f, box)),
                    index * 200L, SpeechLanguage.En, motion = motion,
                )
                if (said != null && "moving" in said) moving += 1
            }
            assertTrue("mouvement réel annoncé ($name)", moving >= 1)
        }
    }
}
