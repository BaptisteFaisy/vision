package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import org.junit.Test

/** TEMPORAIRE diagnostic : pourquoi S1 réel annonce Stop malgré le fond. À supprimer. */
class RealDebugTest {

    private fun loadFrames(): List<List<Detection>> {
        val text = javaClass.classLoader.getResourceAsStream("real-motion/s1-pan.json")
            ?.bufferedReader()?.readText() ?: error("fixture absente")
        val frames = org.json.JSONArray(text)
        return List(frames.length()) { index ->
            val objects = frames.getJSONObject(index).getJSONArray("objects")
            List(objects.length()) { j ->
                val box = objects.getJSONObject(j).getJSONArray("box")
                Detection(
                    objects.getJSONObject(j).getString("label"),
                    objects.getJSONObject(j).getDouble("score").toFloat(),
                    listOf(box.getDouble(0).toFloat(), box.getDouble(1).toFloat(),
                        box.getDouble(2).toFloat(), box.getDouble(3).toFloat()),
                    width = 640, height = 480,
                )
            }
        }
    }

    private fun areaOf(box: List<Float>) = (box[2] - box[0]) * (box[3] - box[1])

    private fun run(name: String, frames: List<List<Detection>>, motion: FrameMotion?) {
        CrossingVehicleWatch.reset()
        val out = StringBuilder("$name:")
        frames.take(6).forEachIndexed { index, detections ->
            val car = detections.filter { it.label == "car" }.maxByOrNull { areaOf(it.box) }!!
            val said = CrossingVehicleWatch.update(
                detections, listOf(DistanceReading(-1, "car", 5f, 5f, car.box)),
                index * 200L, SpeechLanguage.En, motion = motion,
            )
            out.append(" [$index:${said ?: "-"}]")
        }
        println(out.toString())
    }

    @Test
    fun `diagnostic s1 reel`() {
        val frames = loadFrames()
        val biggestOnly = frames.map { dets ->
            listOf(dets.filter { it.label == "car" }.maxByOrNull { areaOf(it.box) }!!)
        }
        val pan = FrameMotion(MotionSample(-0.78f, 0f, 1f, 0.9f), 96, 96, 200L)
        val pan2x = FrameMotion(MotionSample(-1.56f, 0f, 1f, 0.9f), 96, 96, 200L)
        val static = FrameMotion(MotionSample(0f, 0f, 1f, 1f), 96, 96, 200L)
        run("A-tous+pan", frames, pan)
        run("B-maxseul+pan", biggestOnly, pan)
        run("C-tous+pan2x", frames, pan2x)
        run("D-tous+statique", frames, static)
    }
}
