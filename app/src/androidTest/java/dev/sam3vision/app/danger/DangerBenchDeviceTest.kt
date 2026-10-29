package dev.sam3vision.app.danger

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sam3vision.core.depth.syntheticGradientArgb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Banc danger SUR APPAREIL (téléphone réel, jamais d'émulateur) : prouve que
 * YOLO11n infère sur du vrai matériel (CPU) et rend sa médiane (le verdict fps
 * du pipeline urgence). Complémentaire des tests JVM (`app/src/test`, `core`) :
 * eux valident le format du verdict et les maths (pistes, approche, décodage),
 * celui-ci valide l'inférence embarquée.
 *
 * **Aucun workflow ne l'exécute** : la CI n'a pas d'appareil. Vert ici = vert
 * sur *cet* appareil, jamais « vert en CI ».
 *
 * Lancement manuel (r630, téléphone visible par le tunnel adb) :
 * ```bash
 * PHONE_SERIAL=RF8T11NBMCV phone run --wait 600 --task "danger: banc instrumenté" -- \
 *   ssh baptiste@100.124.18.48 "cd builds/sam-wt-danger-approach && \
 *   ANDROID_SERIAL=RF8T11NBMCV ./gradlew :app:connectedDebugAndroidTest \
 *   --tests \"dev.sam3vision.app.danger.DangerBenchDeviceTest\""
 * ```
 * Verdict aussi dans logcat (`DEVICE model=YOLO11n medianMs=…`).
 */
@RunWith(AndroidJUnit4::class)
class DangerBenchDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun yolo11nTourneSurAppareil(): Unit = runBlocking {
        val image = syntheticGradientArgb(SIZE, SIZE)
        val verdict = DangerBench.run(context, image, SIZE, SIZE)
        Log.i(DangerBench.TAG, "DEVICE " + DangerBench.summary(verdict))
        assertTrue("banc YOLO11n : ${verdict.error}", verdict.ok)
        assertEquals("84x8400", verdict.outShape)
    }

    companion object {
        private const val SIZE = 1024
    }
}
