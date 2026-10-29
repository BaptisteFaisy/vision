package dev.sam3vision.app.danger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surface pure du danger (JUnit seul, sans Android ni ORT) : le format du
 * verdict logcat ([DangerBench.summary], parsé par la recette) et la ligne
 * d'alerte ([DangerAlert.line], lue à la voix plus tard).
 */
class DangerBenchTest {

    @Test
    fun `verdict vert avec fps forme de sortie et boites`() {
        val verdict = DangerBenchVerdict(listOf(80, 88, 84, 90, 86), 86, 80, 6, "84x8400", 0, null)
        assertEquals(
            "model=YOLO11n medianMs=86 sess=80 dec=6 runs=5 out=84x8400 boxes=0 fps=11.6",
            DangerBench.summary(verdict),
        )
    }

    @Test
    fun `verdict KO explicite quand le modele est absent`() {
        val verdict = DangerBenchVerdict(emptyList(), 0, 0, 0, "", 0, "modèle absent — asset yolo11n.onnx manquant")
        assertTrue(DangerBench.summary(verdict).startsWith("model=YOLO11n KO — "))
    }

    @Test
    fun `la ligne d'alerte donne le temps-avant-contact arrondi`() {
        assertEquals(
            "⚠ Personne droit devant — contact dans ~3 s",
            DangerAlert(7, 3.4f, 2).line(),
        )
        assertEquals(
            "⚠ Personne droit devant — contact dans ~1 s",
            DangerAlert(7, 0.2f, 1).line(),
        )
    }
}
