package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.decodeScaledExact
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.media.MediaCacheStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La photo-contexte des questions : l'appui long capte la frame filmée, la
 * question suivante la joint au LLM. Pas d'affichage, pas de pastilles : muet.
 *
 * La capture CameraX est asynchrone et la question part après la transcription :
 * sans attente, une tenue courte lisait un contexte encore vide et partait en
 * texte seul — le modèle répondait « aucune image transmise » (vu en base :
 * une question sur deux sans image). [imageBase64] attend donc la capture en
 * vol (borné), et retente une capture si rien n'avait démarré (caméra froide
 * au début de la tenue, prête au moment de lire).
 */
class IseePhotoShot(
    private val media: MediaCacheStore,
    private val camera: () -> IseeCameraActions?,
    private val encode: (File) -> String? = { target ->
        decodeScaledExact(target)?.let(::bitmapToJpegBase64)
    },
    /** Dispatcheur du décodage : I/O en production, dispatcheur de test en JVM. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Attente maximale d'une capture en vol avant de partir en texte seul. */
    private val captureTimeoutMs: Long = CAPTURE_TIMEOUT_MS,
) {

    /** Photo jointe à la prochaine question ; `null` = question sans image. */
    @Volatile
    private var imageFile: File? = null

    /** Capture demandée dont le rappel n'a pas encore répondu (`null` = aucune). */
    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Appui long : capte la frame courante. Une seule capture à la fois : un
     * second appel pendant le vol du premier ne redemande rien. Muet.
     */
    fun captureForHold() {
        if (pending?.isCompleted == false) return
        val cam = camera() ?: return
        val target = media.newPhotoFile()
        val done = CompletableDeferred<Boolean>()
        pending = done
        val accepted = cam.takePhoto(target) { saved ->
            // Relâchée entre-temps (sortie précoce) : le fichier tardif ne doit
            // pas devenir le contexte d'une question suivante.
            if (saved && pending === done) imageFile = target
            done.complete(saved)
        }
        // Refusée (caméra pas prête) : aucun rappel ne viendra, on ne fait pas
        // attendre la lecture — elle retentera, la caméra est peut-être prête.
        if (!accepted) {
            pending = null
            done.complete(false)
        }
    }

    /** Fin de vie du contexte : sortie précoce, question envoyée. */
    fun releaseContext() {
        pending = null
        imageFile = null
    }

    /**
     * La photo-contexte, réencodée en base64 JPEG hors du thread principal ;
     * `null` quand il n'y a rien d'utilisable (pas de photo, fichier purgé ou
     * vide, capture refusée ou trop lente, décodage raté, encodage vide) — une
     * chaîne vide partait au LLM et rendait un 400 muet.
     */
    suspend fun imageBase64(): String? {
        awaitCapture()
        return withContext(io) {
            val target = validFile() ?: return@withContext null
            runCatching { encode(target) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Fichier déjà là : rien à attendre. Sinon on (re)déclenche une capture —
     * la caméra, froide au début de la tenue, est chaude au moment de lire —
     * puis on attend son rappel, borné : au-delà, texte seul, pas d'attente
     * infinie sur une caméra en panne.
     */
    private suspend fun awaitCapture() {
        if (validFile() != null) return
        captureForHold()
        pending?.let {
            val done = withTimeoutOrNull(captureTimeoutMs) { it.join(); true } ?: false
            // Trop lente : son rappel tardif ne doit pas devenir le contexte
            // d'une question suivante (la fin de réponse relâche le fichier).
            if (!done) pending = null
        }
    }

    /** Fichier utilisable : présent sur disque et non vide. */
    private fun validFile(): File? = imageFile?.takeIf { it.isFile && it.length() > 0L }

    companion object {
        /**
         * 4 s : une première capture à froid (convergence 3A) prend 1 à 3 s ;
         * à chaud c'est quasi instantané. Au-delà, la caméra est en panne et la
         * question ne doit pas attendre : elle part en texte seul.
         */
        const val CAPTURE_TIMEOUT_MS = 4_000L
    }
}
