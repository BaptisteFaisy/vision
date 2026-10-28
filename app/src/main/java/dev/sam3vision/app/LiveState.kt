package dev.sam3vision.app

import dev.sam3vision.app.live.PredictionLayer
import dev.sam3vision.app.live.runAsFrame
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.live.Cadence
import dev.sam3vision.core.live.FramePipeline
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.TimingsText
import dev.sam3vision.core.live.TrackRunner
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SeenTrack
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WsLiveClient
import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Locale

/**
 * Mémoire du direct : les objets suivis (avec leur dernière vue), l'instant
 * demandé et la question en cours. C'est ce qui permet de demander « qu'y
 * avait-il à gauche il y a 5 s ? » sans quitter le flux.
 */
data class LiveMemoryState(
    val tracks: List<SeenTrack> = emptyList(),
    val focusSeconds: Int = 0,
    val asking: Boolean = false,
    val question: String = "",
    val answer: String? = null,
    val error: String? = null,
) {
    val empty: Boolean get() = tracks.isEmpty()
}

/**
 * Dernière frame JPEG acceptée, **hors du flux d'état** : le state n'en transporte que la
 * référence. Avant, la chaîne base64 (21-28 Ko) était recopiée et comparée à chaque frame
 * par `copy()`/`equals` de [LiveUiState] — pour un seul bouton (« Analyser la frame »).
 */
class LatestFrame {
    @Volatile
    var jpegB64: String = ""
        private set

    fun set(value: String) {
        jpegB64 = value
    }

    fun clear() {
        jpegB64 = ""
    }

    /** Vrai dès qu'une frame a été gardée par le direct. */
    val present: Boolean get() = jpegB64.isNotEmpty()
}

/** État du mode Direct, observé par [LiveScreen]. */
data class LiveUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val frame: dev.sam3vision.core.model.LiveFrame? = null,
    val overlays: List<android.graphics.Bitmap> = emptyList(),
    val gpu: String = "",
    val fps: Float = 0f,
    val latencyMs: Long = 0,
    val objectsCount: Int = 0,
    val error: String? = null,
    /** Détecteur SAM actif : `false` = vision LLM seule (l'image part directement au LLM). */
    val samEnabled: Boolean = false,
    /**
     * Direct piloté par le **tracker** (`/session` + `/frame`) au lieu de `/live` :
     * les ids d'objets sont stables d'une frame à l'autre. `false` = transport `/live`.
     */
    val trackerEnabled: Boolean = false,
    val prompts: List<String> = emptyList(),
    val memory: LiveMemoryState = LiveMemoryState(),
    val distanceLayer: DistanceLayer? = null,
    /**
     * Boîtes prédites de la frame caméra (T8) : vides tant que la prédiction est
     * éteinte ou que le masque serveur est encore frais — l'overlay suit alors les
     * masques seuls.
     */
    val prediction: PredictionLayer = PredictionLayer(),
    /** Dernière phrase dite par la voix (positions) : affichée comme sous-titre. */
    val spoken: String? = null,
    /** Porteur de la dernière frame JPEG (identité seule : rien à copier d'une frame à l'autre). */
    val latestFrame: LatestFrame = LatestFrame(),
    /** Chronologie de la dernière frame (capture → pixel) + étages serveur reçus. */
    val timings: FrameTimings = FrameTimings(),
    /** Description approfondie du LLM sur la frame courante ("" tant qu'aucune). */
    val narration: String = "",
    val narrationBusy: Boolean = false,
    val narrationError: String? = null,
    /** Voix du direct : on parle pour poser sa question sur le flux en cours. */
    val voice: LiveVoiceState = LiveVoiceState(),
    /** Transport du direct : « WebSocket » (rapide) ou « HTTP » (repli). Vide = inconnu. */
    val transport: String = "",
    /** Calque local : drapeau effectif (réglage **et** build) — voir `localOverlayFlag`. */
    val localEnabled: Boolean = false,
    /** Le calque peint-il en ce moment ? (faux = inerte : sans estimateur, keyframe périmé) */
    val localActive: Boolean = false,
    /** Cadence tenue par le calque local, en ips. */
    val localFps: Float = 0f,
    /** Motif court du dernier refus (« sans estimateur », « keyframe périmé »…). */
    val localNote: String = "",
) {
    /** Une frame a-t-elle déjà été gardée ? (bouton « Analyser la frame », questions). */
    val hasFrame: Boolean get() = latestFrame.present

    /** Chronologie sur **une seule ligne** ; vide tant que rien n'est mesuré. */
    val chronologie: String get() = TimingsText.compact(timings)

    val statusLine: String
        get() = if (running && starting) {
            WAKING_LINE
        } else {
            formatLiveStatus(running, fps, latencyMs, gpu, objectsCount, nearestMeters, samEnabled) + localPart
        }

    /**
     * Part du calque local dans la ligne d'état (contrat de fusion §2.4) : concaténation,
     * [formatLiveStatus] ne change pas (son contrat est testé tel quel).
     */
    val localPart: String get() = localStatusPart(localEnabled, localActive, localFps, localNote)

    /** Distance de l'objet le plus proche de la frame, `null` si rien n'est mesuré. */
    val nearestMeters: Float?
        get() = distanceLayer?.readings?.let { DistanceText.nearest(it)?.meters }
}

/**
 * Distances de la frame courante : taille de l'image analysée (le repère des
 * boîtes, en pixels) et une lecture par objet mesuré. Vide = rien à afficher.
 */
data class DistanceLayer(
    val width: Int = 0,
    val height: Int = 0,
    val readings: List<DistanceReading> = emptyList(),
    /** Champ de vision porté par la largeur, au moment de la mesure (relais site). */
    val fovDeg: Float = 0f,
) {
    /** Vrai si l'overlay n'a rien à poser (pas d'objet calibré, taille inconnue). */
    val empty: Boolean get() = width <= 0 || height <= 0 || readings.isEmpty()
}

/** Ligne du direct en vision seule : pas de détecteur, donc pas de GPU à attendre. */
const val VISION_ONLY_LINE: String = "Direct · vision LLM (l'image part au LLM, sans détecteur)"

/** Ligne affichée pendant le démarrage à froid du conteneur (le clic « Direct » le réveille). */
const val WAKING_LINE: String = "Réveil du GPU A100 40 Go sur Modal…"

/**
 * Barre d'état du direct : « Direct · 1,4 ips · 780 ms · 8 objets · A100-SXM4-40GB ».
 * En vision seule ([samEnabled] = false), il n'y a ni cadence de détection, ni latence
 * de détecteur, ni GPU : la ligne le dit au lieu d'afficher « GPU ? ».
 * Fonction pure, testée en JVM.
 */
fun formatLiveStatus(
    running: Boolean,
    fps: Float,
    latencyMs: Long,
    gpu: String,
    objects: Int,
    nearestMeters: Float? = null,
    samEnabled: Boolean = true,
): String {
    if (!running) return "Direct arrêté"
    if (!samEnabled) return VISION_ONLY_LINE
    val rate = if (fps <= 0f) "…" else String.format(Locale.FRANCE, "%.1f ips", fps)
    val latency = if (latencyMs <= 0) "…" else "$latencyMs ms"
    val nearest = nearestMeters?.let { " · plus proche ${DistanceText.format(it)}" }.orEmpty()
    // Le nom du GPU venait du /health (supprimé le 26/10) : quand il est inconnu, on
    // n'affiche plus « GPU ? » mais rien du tout.
    val chip = if (gpu.isBlank()) "" else " · $gpu"
    return "Direct · $rate · $latency · $objects objets$nearest$chip"
}

/** Cadence de mémorisation de la frame pour les questions : une par seconde suffit. */
const val FRAME_KEEP_MS = 1_000L

/**
 * Vrai si la frame courante doit être gardée pour les questions. Indépendant de SAM :
 * même sans détecteur, la dernière image permet de demander « qu'y a-t-il devant ? ».
 */
fun shouldKeepFrame(nowMs: Long, lastKeepAtMs: Long, intervalMs: Long = FRAME_KEEP_MS): Boolean =
    lastKeepAtMs <= 0L || nowMs - lastKeepAtMs >= intervalMs

/**
 * État après un échec réseau : compteur remis à zéro, transport reflétant le socket —
 * et erreur **masquée** pendant le réveil GPU au lieu d'affoler l'écran.
 */
fun frameErrorState(current: LiveUiState, waking: Boolean, transport: String, message: String?): LiveUiState =
    current.copy(
        objectsCount = 0,
        transport = transport,
        starting = waking,
        error = if (waking) null else samUnreachableLine(message ?: "GPU injoignable"),
    )

/** Cadence du prochain tick, bornée par les constantes de [LiveSession]. */
fun nextIntervalMs(session: LiveSession?): Long = session?.intervalMsAdaptive() ?: Cadence.Direct.targetMs

/** Demi-largeur de la fenêtre demandée autour de « il y a N s » : ±2,5 s. */
const val FOCUS_HALF_SPAN_MS = 2_500L

/** Instants proposés dans l'UI, en secondes écoulées (0 = maintenant). */
val FOCUS_CHOICES = listOf(0, 5, 15, 30)

/** Fenêtre observée pour « il y a [seconds] s », élargie de part et d'autre. */
fun focusWindow(nowMs: Long, seconds: Int, halfSpanMs: Long = FOCUS_HALF_SPAN_MS): LongRange {
    val target = nowMs - seconds.coerceAtLeast(0) * 1_000L
    return (target - halfSpanMs)..(target + halfSpanMs)
}

/**
 * Libellé du point focal, affiché sur les pastilles et **envoyé au LLM**.
 *
 * Le demi-intervalle suit la langue : une virgule décimale dans une consigne anglaise se
 * lit « deux virgule cinq » là où l'on veut « two point five » (la réponse est lue par
 * la voix). L'appelant passe donc la langue de la voix.
 */
fun focusLabel(
    seconds: Int,
    language: SpeechLanguage = SpeechLanguage.Fr,
    halfSpanMs: Long = FOCUS_HALF_SPAN_MS,
): String {
    val locale = if (language == SpeechLanguage.En) Locale.US else Locale.FRANCE
    val half = String.format(locale, "%.1f", halfSpanMs / 1_000.0)
    return when {
        seconds <= 0 && language == SpeechLanguage.En -> "Right now (last $half s)"
        seconds <= 0 -> "À l'instant (dernières $half s)"
        language == SpeechLanguage.En -> "$seconds s ago (± $half s)"
        else -> "Il y a $seconds s (± $half s)"
    }
}

/** Fenêtre du journal envoyée au LLM : 30 s suffisent pour « il y a 5 s ». */
internal const val MEMORY_WINDOW_MS = 30_000L

/** Dossier audio de l'annonce des positions : distinct de celui des bandeaux. */
internal const val POSITION_VOICE_DIR = "positions"

/**
 * « Tracker » en mode sessions, sinon « WebSocket » ou « WebSocket KO » (aucun repli POST) :
 * lit l'état du socket sur le client, la table des libellés restant pure et testée en JVM
 * ([transportLabel] à drapeau, dans `LiveMessages`).
 */
internal fun transportLabel(trackerEnabled: Boolean, client: LiveClient?): String =
    transportLabel(trackerEnabled, (client as? WsLiveClient)?.isConnected == true)

/**
 * Envoie la frame au pipeline actif, quel que soit le transport ; `null` = frame jetée
 * (créneau pris — la caméra n'attend jamais le réseau).
 */
internal suspend fun FramePipeline.sendFrame(prompts: List<String>, jpegB64: String): Result<LiveFrame>? =
    when (this) {
        is TrackRunner -> runAsFrame(prompts, jpegB64)
        is LiveSession -> runFrame(jpegB64)
        else -> null
    }