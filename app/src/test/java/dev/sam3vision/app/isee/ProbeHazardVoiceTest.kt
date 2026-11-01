package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardSignal
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ligne diagnostique `HazardVoice` : lectures parlées, signal retenu, phrase. */
class ProbeHazardVoiceTest {

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    @Test
    fun `ligne resume lectures signal et phrase`() {
        val readings = listOf(
            reading("person", 12f, listOf(220f, 300f, 420f, 460f)),
            reading("poteau", 14f, listOf(300f, 100f, 340f, 400f)),
            reading("car", 6f, listOf(0f, 0f, 100f, 100f)),
        )
        val signal = HazardSignal(detected = true, meters = 12f, label = "person", centerX = 0.5f)

        val line = hazardVoiceLine(readings, signal, "Droite.")

        assertTrue(line.contains("lectures=3"))
        assertTrue(line.contains("person/12.0m/cx=320.0"))
        assertTrue(line.contains("poteau/14.0m/cx=320.0"))
        assertTrue(line.contains("signal=true/12.0m/person/cx=0.5"))
        assertTrue(line.contains("phrase=Droite."))
    }

    @Test
    fun `ligne muette sans lecture parlee`() {
        val readings = listOf(reading("car", 6f, listOf(0f, 0f, 100f, 100f)))

        val line = hazardVoiceLine(readings, HazardSignal.none(), null)

        assertTrue(line.contains("dangers=[]"))
        assertTrue(line.contains("signal=false/nullm/null"))
        assertTrue(line.contains("phrase=null"))
    }
}
