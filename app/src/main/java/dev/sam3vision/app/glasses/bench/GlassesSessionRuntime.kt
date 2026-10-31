package dev.sam3vision.app.glasses.bench

import android.app.Activity
import android.content.Context
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.DatError
import dev.sam3vision.app.glasses.JpegFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * État d'un cycle lunettes : recréé à chaque [GlassesSession.start], jeté à
 * chaque arrêt.
 *
 * Porte ce que les phases partagent — le canal de frames, le lancement protégé,
 * la bascule IO, le message d'erreur lisible, la libération DAT — pour que
 * [GlassesRegistrationPhase], [GlassesDeviceSession] et [GlassesFramePump]
 * n'aient aucun état propre. Les `*Latch` sont les garde-fous « une seule fois
 * par cycle » (`CycleLatch`, testé sur JVM).
 */
internal class GlassesRuntime(
    private val appContext: Context,
    val job: CompletableJob,
    val scope: CoroutineScope,
    val activity: WeakReference<Activity>,
    val encoder: GlassesFrameEncoder,
    val onFrame: suspend (JpegFrame) -> Unit,
    val onLog: (String) -> Unit,
    /** Arrêt du cycle complet (erreur critique du flux) : câblé sur `GlassesSession.stop`. */
    private val requestStop: () -> Unit,
) {
    /** Conflaté : la frame la plus récente écrase l'attente, rien ne s'empile. */
    val frames = Channel<VideoFrame>(Channel.CONFLATED)

    var session: DeviceSession? = null
    var camera: Camera? = null
    var stream: Stream? = null
    /** Un enregistrement, une session et une caméra au plus par cycle. */
    val registrationLatch = CycleLatch()
    val sessionLatch = CycleLatch()
    val cameraLatch = CycleLatch()

    /**
     * Tout job interne passe par là : une exception du SDK ou d'un callback de
     * l'appelant doit être loggée, pas tuer le cycle ni remonter au scope.
     */
    fun launchSafe(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend () -> Unit,
    ) {
        scope.launch(start = start) {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                onLog("internal incident: ${error.message}")
            }
        }
    }

    /** Les appels DAT peuvent bloquer (négociation, handshake) : jamais sur le main. */
    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Message lisible pour l'UI, avec repli sur la description brute du SDK. */
    fun describe(error: DatError): String =
        runCatching { error.getLocalizedDescription(appContext) }.getOrElse { error.description }

    /** Coupe le cycle complet, y compris les collecteurs et la session. */
    fun stopCycle() = requestStop.invoke()

    /**
     * Libère flux, caméra puis session, sur un scope jetable dédié : le scope de
     * l'appelant peut être annulé au même instant (fermeture d'écran), la
     * libération doit aboutir quand même.
     */
    fun release() {
        // Instantané pris à l'appel : la libération ne doit viser que les
        // ressources du cycle arrêté, pas celles qu'un startStream tardif poserait.
        val stream = stream
        val camera = camera
        val session = session
        val cleanup = SupervisorJob()
        CoroutineScope(cleanup + Dispatchers.IO).launch {
            try {
                stream?.stop()
                camera?.stop()
                session?.stop()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                onLog("error while stopping: ${error.message}")
            } finally {
                cleanup.cancel()
            }
        }
    }
}