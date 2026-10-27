package dev.sam3vision.app.glasses.bench

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Preuve **liée à l'appareil** : une frame sort-elle du flux simulé du harnais mock,
 * sans aucune paire de lunettes physique ?
 *
 * Ce qu'il exige :
 * - un appareil Android réel ou un émulateur en ligne : le SDK Device Access Toolkit
 *   (DAT, `MockDeviceKit`) a besoin du `PackageManager` et de l'enregistrement d'appareil ;
 *   aucun runner GitHub hébergé ne le fournit sans device managé ;
 * - l'asset `mock_feed.mp4` (`app/src/androidTest/assets/`, vidéo h.265 de 3 s, 540×960,
 *   copiée dans le cache à l'exécution) : ni caméra, ni capteur, ni lunettes réelles ;
 * - deux délais de 20 s (apparition de l'appareil simulé, première frame) : compter ~40 s
 *   avant un échec.
 *
 * **Aucun workflow ne l'exécute** : la CI ne fait que `:app:testDebugUnitTest` et
 * `:app:assembleDebug`. Vert ici = vert sur *cet* appareil, jamais « vert en CI ».
 *
 * Lancement manuel (émulateur ou téléphone branché) :
 * ```bash
 * ./gradlew :app:connectedDebugAndroidTest --offline
 * # ou, après `:app:assembleDebug :app:assembleDebugAndroidTest` :
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w \
 *   -e class dev.sam3vision.app.glasses.bench.MockGlassesTest \
 *   dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * Le paquet de test (`<applicationId>.test`, ici `dev.sam3vision.app.test`) est posé par
 * le `testInstrumentationRunner` de `app/build.gradle.kts` : le vérifier avec
 * `adb shell pm list instrumentation` plutôt que de le recopier d'un autre module.
 */
@RunWith(AndroidJUnit4::class)
class MockGlassesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val log: (String) -> Unit = { line -> logs.add(line) }

    private var session: DeviceSession? = null
    private var stream: Stream? = null

    @After
    fun tearDown() {
        runCatching { stream?.stop() }
        stream = null
        runCatching { session?.stop() }
        session = null
        MockGlassesHarness.disable()
        // Le journal du harnais reste lisible dans logcat même en cas de succès.
        logs.forEach { Log.i(TAG, "harnais : $it") }
    }

    @Test
    fun uneFrameArriveDuFluxSimule() = withLogsOnFailure {
        assertTrue("activation du harnais", MockGlassesHarness.enable(context, log))
        // « Mock visible » : le SDK ne voit la paire simulée qu'une fois enregistré.
        runBlocking { awaitMockVisible() }

        val video = copyAssetToCache(VIDEO_ASSET)
        assertTrue(
            "vidéo h.265 non armée comme flux caméra",
            MockGlassesHarness.useVideoFeed(Uri.fromFile(video), log),
        )

        runBlocking { collectFirstFrame() }
        assertMockGone()
    }

    /** Ouvre une session DAT, démarre le flux et attend la première frame. */
    private suspend fun collectFirstFrame() = coroutineScope {
        val deviceSession = Wearables.createSession(AutoDeviceSelector())
            .getOrThrow()
            .also { session = it }
        deviceSession.start()
        withTimeout(SESSION_TIMEOUT_MS) {
            deviceSession.state.first { it == DeviceSessionState.STARTED }
        }

        val camera = deviceSession.addCamera(StreamConfiguration()).getOrThrow()
        val cameraStream = camera.stream.also { stream = it }
        cameraStream.start().getOrThrow()

        val latch = CountDownLatch(1)
        val collector = launch(Dispatchers.IO) {
            cameraStream.videoStream.first()
            latch.countDown()
        }
        val frameArrived = withContext(Dispatchers.IO) {
            latch.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        collector.cancel()
        cameraStream.stop()
        camera.stop()
        stream = null

        assertTrue("aucune frame reçue en $FRAME_TIMEOUT_SECONDS s", frameArrived)
    }

    /**
     * « Mock visible » : le SDK ne liste la paire simulée qu'une fois enregistré, et
     * l'enregistrement remonte de façon asynchrone — d'où l'attente avant le premier
     * `NO_ELIGIBLE_DEVICE`.
     */
    private suspend fun awaitMockVisible() {
        withTimeout(SESSION_TIMEOUT_MS) {
            Wearables.devices.first { it.isNotEmpty() }
        }
    }

    /**
     * « Mock invisible » : le premier retrait journalise la paire supprimée, le second
     * reste muet — `dropSimulatedPairs` ne parle que s'il restait quelque chose. Une
     * paire simulée oubliée fausserait ensuite le mode « vraies lunettes ».
     */
    private fun assertMockGone() {
        runCatching { session?.stop() }
        session = null
        val beforeDrop = logs.size
        MockGlassesHarness.dropSimulatedPairs(context, log)
        val afterDrop = logs.size
        MockGlassesHarness.dropSimulatedPairs(context, log)
        assertTrue("la paire simulée du test n'a pas été retirée du SDK", afterDrop > beforeDrop)
        assertTrue("le mock est toujours apparié après dropSimulatedPairs()", logs.size == afterDrop)
    }

    /** Les assets androidTest voyagent dans l'APK de test, pas dans celui de l'app. */
    private fun copyAssetToCache(name: String): File {
        val target = File(context.cacheDir, name)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.open(name).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    /** Rejoue l'échec en y joignant le journal du harnais (diagnostic). */
    private inline fun withLogsOnFailure(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            throw AssertionError("échec ; journal du harnais :\n${logs.joinToString("\n")}", t)
        }
    }

    private companion object {
        const val TAG = "MockGlassesTest"
        const val VIDEO_ASSET = "mock_feed.mp4"
        const val SESSION_TIMEOUT_MS = 20_000L
        const val FRAME_TIMEOUT_SECONDS = 20L
    }
}