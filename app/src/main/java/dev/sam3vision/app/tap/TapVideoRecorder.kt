package dev.sam3vision.app.tap

import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * Enregistreur vidéo du mode tap, derrière une interface pour que [TapSession] se
 * teste sur JVM avec un faux (CameraX n'y est pas). Prod : [TapVideoSession].
 */
interface TapVideoRecorder {
    /** Vrai quand la caméra vidéo est liée et prête à démarrer. */
    val ready: Boolean

    /** Lie la capture vidéo au cycle de vie de l'écran. */
    suspend fun bind(owner: LifecycleOwner?): Result<Unit>

    /** Démarre l'enregistrement vers [file] ([withAudio] ajoute la piste micro). */
    fun start(file: File, withAudio: Boolean = TapConfig.VIDEO_WITH_AUDIO): Result<Unit>

    /** Arrête l'enregistrement sans attendre la finalisation du fichier. */
    fun stop()

    /** Arrête et attend le fichier finalisé (`null` : échec ou délai dépassé). */
    suspend fun stopAndAwait(file: File, timeoutMs: Long = FINALIZE_TIMEOUT_MS): File?

    /** Libère caméra et exécuteur (écran quitté). */
    fun release()

    companion object {
        /** Attente max de la finalisation du clip par CameraX. */
        const val FINALIZE_TIMEOUT_MS = 4_000L
    }
}
