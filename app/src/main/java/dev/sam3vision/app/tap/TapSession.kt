package dev.sam3vision.app.tap

import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import dev.sam3vision.app.MicBus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Ce que l'écran affiche (en gros texte) pendant et après l'interaction. */
sealed interface TapStatus {
    data object Idle : TapStatus
    data object Recording : TapStatus
    data object Sending : TapStatus
    data class Sent(val summary: String) : TapStatus
    data class Failed(val reason: String) : TapStatus
}

/**
 * Chef d'orchestre du mode tap : tap court → [TapVideoRecorder] démarre → annonce
 * « Launched » ; second tap → arrêt, envoi à SAM 3.1, annonce « Sent » (ou « Failed »).
 *
 * Aucun autre son n'est produit ici : [AnnouncementPlayer] ne connaît que les trois mots,
 * et il lit sur sa propre sortie audio sans demander le focus — la piste audio de la
 * vidéo n'est donc ni coupée ni ducker.
 *
 * La machine à états est pure ([tapCommand]/[tickCommand]) : ce qui reste ici, c'est le
 * câblage (fichier, caméra, réseau) — d'où des fonctions courtes et testables.
 */
class TapSession(
    private val scope: CoroutineScope,
    private val video: TapVideoRecorder,
    private val announcements: AnnouncementPlayer,
    private val sender: suspend (File) -> Result<String>,
    private val onStatus: (TapStatus) -> Unit,
    /** Fichier de la prise, fourni par l'écran (jamais de `Context` ici : testable sur JVM). */
    private val newVideoFile: () -> File,
    /** Permissions déjà vérifiées par l'écran ; injectées pour les tests JVM. */
    private val permissionsGranted: () -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val owner: () -> LifecycleOwner? = { null },
    /** Raison de blocage d'envoi (`null` = envoi possible). Testé avec un lambda fixe. */
    private val sendBlocked: () -> String? = TapConfig::sendBlockedReason,
) {
    private var phase: TapPhase = TapPhase.IDLE
    private var startedAt: Long = 0L
    private var currentFile: File? = null
    private var ticker: Job? = null
    private var starting = false
    private var stopRequested = false

    /**
     * Un tap court (déclenché au relâchement par [TapAreaView]). **Aucun tap n'est perdu** :
     * arrivé pendant la liaison caméra ou trop tôt en cours d'enregistrement, il est mémorisé
     * et exécuté par le battement dès que la durée minimale est atteinte (~350 ms).
     */
    fun onTap() {
        if (starting) {
            stopRequested = true
            return
        }
        val command = tapCommand(snapshot())
        // Envoi impossible (endpoint vide, on-device) : échec explicite immédiat,
        // sans enregistrer un clip qui ne partirait jamais.
        val blocked = sendBlocked()
        if (command == TapCommand.START && blocked != null) {
            fail(blocked, Announcement.FAILED)
            return
        }
        if (command == TapCommand.NONE && phase == TapPhase.RECORDING) stopRequested = true
        dispatch(command)
    }

    /** Battement périodique : exécute l'arrêt mémorisé, et arrête au-delà de [TapConfig.MAX_RECORDING_MS]. */
    fun onTick() = dispatch(tickCommand(snapshot(), stopRequested))

    /** Arrêt propre (écran quitté) : jamais d'enregistrement orphelin. */
    fun release() {
        ticker?.cancel()
        ticker = null
        if (phase == TapPhase.RECORDING) video.stop()
        currentFile = null
        phase = TapPhase.IDLE
        stopRequested = false
        MicBus.release(MicBus.Owner.TAP_VIDEO)
        announcements.stop()
    }

    private fun snapshot(): TapSnapshot =
        TapSnapshot(phase, if (phase == TapPhase.RECORDING) clock() - startedAt else 0L)

    private fun dispatch(command: TapCommand) = when (command) {
        TapCommand.START -> start()
        TapCommand.STOP_AND_SEND -> stopAndSend()
        TapCommand.NONE, TapCommand.IGNORE -> Unit
    }

    /**
     * Démarre l'enregistrement, puis annonce « Launched ». La liaison caméra est relancée
     * à la demande : un tap arrivé avant la fin de la liaison initiale (opening ~1 s sur
     * émulateur) ne doit jamais devenir un « Failed » pour l'utilisateur.
     */
    private fun start() {
        if (starting) return
        starting = true
        scope.launch {
            try {
                if (!permissionsGranted()) {
                    fail("permission caméra ou micro refusée", Announcement.FAILED)
                    return@launch
                }
                if (!video.ready && video.bind(owner()).isFailure) {
                    fail("caméra vidéo indisponible", Announcement.FAILED)
                    return@launch
                }
                record()
            } finally {
                starting = false
            }
        }
    }

    /** Corps du démarrage, une fois la caméra liée et les permissions acquises. */
    private fun record() {
        val needsMic = TapConfig.VIDEO_WITH_AUDIO
        if (needsMic && !MicBus.acquire(MicBus.Owner.TAP_VIDEO)) {
            fail("micro occupé par la dictée", Announcement.FAILED)
            return
        }
        val file = newVideoFile()
        val started = video.start(file, withAudio = needsMic)
        if (started.isFailure) {
            if (needsMic) MicBus.release(MicBus.Owner.TAP_VIDEO)
            fail(started.exceptionOrNull()?.message ?: "démarrage vidéo refusé", Announcement.FAILED)
            return
        }
        phase = TapPhase.RECORDING
        startedAt = clock()
        currentFile = file
        onStatus(TapStatus.Recording)
        announce(Announcement.LAUNCHED)
        startTicker()
    }

    /**
     * Arrête, **attend que le fichier soit écrit**, puis l'envoie à SAM 3.1. Annonce « Sent »
     * au succès, « Failed » au moindre échec. L'attente est bornée : un enregistreur muet ne
     * bloque pas la session.
     */
    private fun stopAndSend() {
        val file = currentFile
        ticker?.cancel()
        ticker = null
        stopRequested = false
        phase = TapPhase.SENDING
        currentFile = null
        onStatus(TapStatus.Sending)
        scope.launch { sendPrepared(file) }
    }

    private suspend fun sendPrepared(file: File?) {
        val ready = if (file == null) null else video.stopAndAwait(file)
        // La prise est écrite : le micro est libre, la dictée peut reprendre pendant l'envoi.
        MicBus.release(MicBus.Owner.TAP_VIDEO)
        if (ready == null) {
            fail("aucune vidéo à envoyer", Announcement.FAILED)
            return
        }
        upload(ready)
    }

    /** Envoi borné dans le temps ; toute issue non-success devient une annonce « Failed ». */
    private suspend fun upload(file: File) {
        val outcome = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) { sender(file) }
        phase = TapPhase.IDLE
        outcome?.fold(
            onSuccess = { summary ->
                onStatus(TapStatus.Sent(summary))
                announce(Announcement.SENT)
            },
            onFailure = { error -> fail(error.message ?: "envoi refusé", Announcement.FAILED) },
        ) ?: fail("délai d'envoi dépassé", Announcement.FAILED)
    }

    /** Échec : libellé à l'écran + mot annoncé (jamais autre chose que les trois mots). */
    private fun fail(reason: String, announce: Announcement) {
        phase = TapPhase.IDLE
        onStatus(TapStatus.Failed(reason))
        this.announce(announce)
    }

    private fun announce(word: Announcement) {
        scope.launch { announcements.speak(word) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (phase == TapPhase.RECORDING) {
                delay(TICK_MS)
                onTick()
            }
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val UPLOAD_TIMEOUT_MS = 130_000L
    }
}
