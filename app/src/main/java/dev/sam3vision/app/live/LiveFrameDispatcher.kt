package dev.sam3vision.app.live

import dev.sam3vision.core.live.FramePayload
import dev.sam3vision.core.live.FramePipeline
import dev.sam3vision.core.live.LivePump
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.TrackRunner
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Envois du Direct : qui part, quand, et ce qui suit la réponse.
 *
 * Session `/live` ([LiveSession]) : [LivePump] — cadence continue (moitié de la latence,
 * 33-900 ms) et réserve d'une frame, renvoyée **sans attendre** dès la réponse (u01 : fin
 * de l'échelle 350/500/900, −71 ms par frame). Tracker (autre [FramePipeline]) :
 * [LiveFrameCadence] adaptative, la pompe ne connaissant que les créneaux `LiveSession`.
 *
 * Extrait de `LiveViewModel` (plafond 500 lignes) : la décision est testable en JVM,
 * l'écran ne garde que la publication.
 */
class LiveFrameDispatcher(
    private val scope: CoroutineScope,
    /** Exécuteur des requêtes : jamais la boucle de composition. */
    private val worker: CoroutineDispatcher,
    private val session: () -> LiveSession?,
    private val prompts: () -> List<String>,
    /** Frame acceptée : chronologie + garde pour les questions. */
    private val onDispatch: (jpegB64: String, captureAtNs: Long) -> Unit,
    /** Réponse (ou `null` si jetée) : l'époque est vérifiée avant publication. */
    private val onResult: suspend (active: FramePipeline, era: Long, result: Result<LiveFrame>?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Point de feed adaptatif (Q8+Q9) : aller-retour mesuré, réponse (`null` = jetée
     * sans envoi) et poids base64 — le régulateur et le scheduler apprennent ici.
     */
    private val onSettled: ((roundTripMs: Long, result: Result<LiveFrame>?, base64Length: Int) -> Unit)? = null,
) {
    private val pump = LivePump(session, clock)
    private val trackerCadence = LiveFrameCadence()
    private val reserved = FramePayload()

    /** Bras B de la cadence tracker (continue au lieu d'adaptative), posé à chaque séance. */
    var continuousCadence: Boolean
        get() = trackerCadence.continuous
        set(value) { trackerCadence.continuous = value }

    /** Nouvelle séance : échéances et réserve remises à zéro, bras de cadence posé. */
    fun reset(continuous: Boolean = false) {
        pump.reset()
        trackerCadence.reset()
        trackerCadence.continuous = continuous
    }

    /**
     * Faut-il encoder la frame qui arrive ? Même admission que [onFrame], en lecture
     * seule (motif `IseeCameraPane`) : la pompe pour `/live`, la cadence pour le tracker.
     */
    fun wantsFrame(pipeline: FramePipeline?, nowMs: Long): Boolean {
        val active = pipeline ?: return false
        return if (active is LiveSession) pump.wantsFrame() else trackerCadence.wantsFrame(active, nowMs)
    }

    /**
     * Frame caméra : `false` = en réserve (requête en vol, `/live`) ou jetée — rien ne
     * part, rien ne bloque. `true` = envoyée, la réponse suivra dans [onResult].
     */
    fun onFrame(pipeline: FramePipeline?, jpegB64: String, captureAtNs: Long, nowMs: Long): Boolean {
        val active = pipeline ?: return false
        if (jpegB64.isBlank()) return false
        if (active is LiveSession) {
            if (!pump.offer(jpegB64, captureAtNs)) return false
        } else if (!trackerCadence.admits(active, nowMs)) return false
        dispatch(active, jpegB64, captureAtNs)
        return true
    }

    /** Envoie maintenant, puis renvoie la réserve dès la réponse (créneau rendu). */
    private fun dispatch(active: FramePipeline, jpegB64: String, captureAtNs: Long) {
        onDispatch(jpegB64, captureAtNs)
        val era = active.generation
        scope.launch(worker) {
            val startedAt = clock()
            val result = send(active, jpegB64)
            onSettled?.invoke(clock() - startedAt, result, jpegB64.length)
            // La réserve part **avant** la publication : le décodage des masques ne doit
            // pas laisser le GPU inactif (même ordre que la sonde iSee). Séance close
            // entre-temps : pas de drain, la réponse périmée est filtrée par l'écran.
            if (active.isCurrent(era)) drain()
            onResult(active, era, result)
        }
    }

    /**
     * Envoie la frame au pipeline actif, quel que soit le transport ; `null` = frame
     * jetée (créneau pris — la caméra n'attend jamais le réseau).
     */
    private suspend fun send(active: FramePipeline, jpegB64: String): Result<LiveFrame>? =
        when (active) {
            is TrackRunner -> active.runAsFrame(prompts(), jpegB64)
            is LiveSession -> active.runFrame(jpegB64)
            else -> null
        }

    /** Réponse arrivée : la réserve part tout de suite (session `/live` seule). */
    private fun drain() {
        if (!pump.send(reserved)) return
        val live = session() ?: return
        dispatch(live, reserved.jpegB64, reserved.captureAtNs)
    }
}
