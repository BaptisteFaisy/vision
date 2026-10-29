package dev.sam3vision.app.depth

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.depth.syntheticGradientArgb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Banc profondeur SUR APPAREIL (téléphone réel, jamais d'émulateur) : prouve que
 * le moteur télécharge son modèle (release GitHub, ~26 Mo au premier run) et
 * infère sur du vrai matériel (CPU, NNAPI absent).
 * Complémentaire des tests JVM (`app/src/test`) : eux valident le
 * prétraitement, celui-ci valide le téléchargement + l'inférence embarquée.
 *
 * **Aucun workflow ne l'exécute** : la CI n'a pas d'appareil. Vert ici = vert
 * sur *cet* appareil, jamais « vert en CI ».
 *
 * Lancement manuel (r630, téléphone visible par le tunnel adb) :
 * ```bash
 * phone take --task "depth: banc instrumenté"   # depuis le poste
 * cd builds/<worktree> && ANDROID_SERIAL=2c6852ac \
 *   ./gradlew :app:connectedDebugAndroidTest \
 *   --tests "dev.sam3vision.app.depth.DepthBenchDeviceTest"
 * phone release
 * ```
 * Verdicts aussi dans logcat (`DEVICE backend=DA2 medianMs=…`).
 */
@RunWith(AndroidJUnit4::class)
class DepthBenchDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun da2SmallTourneSurAppareil(): Unit = runBlocking {
        runBench(DepthBackend.DA2)
    }

    private suspend fun runBench(backend: DepthBackend) {
        val ensured = ModelDownloader.ensure(context, backend)
        assertTrue("téléchargement ${backend.code} : ${ensured.exceptionOrNull()?.message}", ensured.isSuccess)
        val estimator = DepthEstimators.open(context, backend)
        try {
            val image = syntheticGradientArgb(256, 256)
            val verdict = DepthBench.run(estimator, image, 256, 256)
            Log.i(DepthBench.TAG, "DEVICE " + DepthBench.summary(verdict))
            assertTrue("banc ${backend.code} : ${verdict.error}", verdict.ok)
            assertTrue("carte vide", verdict.mapWidth > 0 && verdict.mapHeight > 0)
        } finally {
            estimator.close()
        }
    }
}
