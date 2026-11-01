package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import org.junit.Test

/** TEMPORAIRE diagnostic 2 : le bord de cadre cause-t-il l'alarme f19 ? À supprimer. */
class RealDebug2Test {

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

    private fun touchesEdge(box: List<Float>): Boolean =
        box[0] <= 1f || box[2] >= 639f || box[1] <= 1f || box[3] >= 479f

    private fun run(name: String, frames: List<List<Detection>>) {
        CrossingVehicleWatch.reset()
        val motion = FrameMotion(MotionSample(-0.78f, 0f, 1f, 0.9f), 96, 96, 200L)
        val out = StringBuilder("$name:")
        frames.forEachIndexed { index, detections ->
            val cars = detections.filter { it.label == "car" }
            val box = cars.maxByOrNull { (it.box[2] - it.box[0]) * (it.box[3] - it.box[1]) }?.box
                ?: listOf(0f, 0f, 1f, 1f)
            val said = CrossingVehicleWatch.update(
                detections, listOf(DistanceReading(-1, "car", 5f, 5f, box)),
                index * 200L, SpeechLanguage.En, motion = motion,
            )
            if (index in 15..25) out.append(" [$index:${said ?: "-"}]")
        }
        println(out.toString())
    }

    @Test
    fun `diagnostic bord de cadre`() {
        val frames = loadFrames()
        run("A-tout", frames)
        run("E-sansbords", frames.map { dets -> dets.filterNot { touchesEdge(it.box) } })
    }
}
