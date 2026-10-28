package dev.sam3vision.app.detect

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sam3vision.app.depth.ModelDownloader
import dev.sam3vision.core.depth.syntheticGradientArgb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Banc détection SUR APPAREIL (téléphone réel, jamais d'émulateur) : prouve
 * que le modèle se télécharge (release GitHub, 4,8 Mo au premier run) et
 * infère sur du vrai matériel. Complémentaire des tests JVM : eux valident
 * le prétraitement et le décodage, celui-ci valide téléchargement + ORT.
 *
 * **Aucun workflow ne l'exécute** : la CI n'a pas d'appareil.
 *
 * Lancement manuel (r630, téléphone visible par le tunnel adb) :
 * ```bash
 * ~/bin/phone take --task "nanodet: banc instrumenté" --ttl 15m   # depuis le poste
 * cd builds/<worktree> && ANDROID_SERIAL=2c6852ac \
 *   ./gradlew :app:connectedDebugAndroidTest \
 *   --tests "dev.sam3vision.app.detect.NanoDetBenchDeviceTest"
 * ~/bin/phone release
 * ```
 * Verdict aussi dans logcat (`DEVICE nanodet medianMs=…`).
 */
@RunWith(AndroidJUnit4::class)
class NanoDetBenchDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nanodetTourneSurAppareil(): Unit = runBlocking {
        val ensured = ModelDownloader.ensureSpec(
            context.filesDir,
            DetectModels.NANODET,
            dir = DetectModelStore.DIR,
        )
        assertTrue("téléchargement : ${ensured.exceptionOrNull()?.message}", ensured.isSuccess)
        val detector = OnnxNanoDetDetector.open(context)
        assertTrue("ouverture du modèle", detector != null)
        try {
            val image = syntheticGradientArgb(320, 320)
            val times = mutableListOf<Long>()
            var boxes = -1
            repeat(11) { i ->
                val result = detector!!.detect(image, 320, 320)
                assertTrue("inférence : ${result.exceptionOrNull()?.message}", result.isSuccess)
                val got = result.getOrThrow()
                if (i > 0) times.add(got.inferenceMs)
                boxes = got.boxes.size
            }
            times.sort()
            Log.i("sam3vision", "DEVICE nanodet backend=NANODET medianMs=${times[times.size / 2]} boxes=$boxes")
        } finally {
            detector?.close()
        }
    }
}
