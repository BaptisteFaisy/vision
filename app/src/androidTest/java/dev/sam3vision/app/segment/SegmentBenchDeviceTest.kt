package dev.sam3vision.app.segment

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
 * Banc segmentation SUR APPAREIL (téléphone réel, jamais d'émulateur) : prouve
 * que le modèle se télécharge (release GitHub, 3,1 Mo au premier run) et
 * infère sur du vrai matériel. Complémentaire des tests JVM : eux valident
 * le prétraitement et le décodage, celui-ci valide téléchargement + LiteRT.
 *
 * **Aucun workflow ne l'exécute** : la CI n'a pas d'appareil.
 *
 * Lancement manuel (r630, téléphone visible par le tunnel adb) :
 * ```bash
 * ~/bin/phone take --task "twinlite: banc instrumenté" --ttl 15m   # depuis le poste
 * cd builds/<worktree> && ANDROID_SERIAL=2c6852ac \
 *   ./gradlew :app:connectedDebugAndroidTest \
 *   --tests "dev.sam3vision.app.segment.SegmentBenchDeviceTest"
 * ~/bin/phone release
 * ```
 * Verdict aussi dans logcat (`DEVICE twinlite medianMs=…`).
 */
@RunWith(AndroidJUnit4::class)
class SegmentBenchDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun twinliteTourneSurAppareil(): Unit = runBlocking {
        val ensured = ModelDownloader.ensureSpec(
            context.filesDir,
            SegmentModels.TWINLITE,
            dir = SegmentModelStore.DIR,
        )
        assertTrue("téléchargement : ${ensured.exceptionOrNull()?.message}", ensured.isSuccess)
        val segmenter = LiteRtTwinLiteSegmenter.open(context)
        assertTrue("ouverture du modèle", segmenter != null)
        try {
            val image = syntheticGradientArgb(640, 360)
            val times = mutableListOf<Long>()
            var cover = -1f
            repeat(11) { i ->
                val result = segmenter!!.segment(image, 640, 360)
                assertTrue("inférence : ${result.exceptionOrNull()?.message}", result.isSuccess)
                val got = result.getOrThrow()
                if (i > 0) times.add(got.inferenceMs)
                cover = got.driveable.coverage()
            }
            times.sort()
            Log.i("sam3vision", "DEVICE twinlite backend=TWINLITE medianMs=${times[times.size / 2]} cover=$cover")
        } finally {
            segmenter?.close()
        }
    }
}
