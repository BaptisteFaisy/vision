package dev.sam3vision.core.stairs

import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Traitement escaliers d'une frame caméra, appelé à chaque tick du direct :
 * [luma] doit être un plan [LumaPlane] complet (256×103), sinon le signal est
 * vide (sûr : une frame inexploitable casse la série au lieu de confirmer).
 *
 * L'annonce confirmée reste affichée [HOLD_MS] (le cooldown voix, lui, vit
 * dans [StairWatcher]) : sans maintien, la phrase ne vivrait qu'un tick.
 * La voix se branche par [observe] : le [StairCue] y porte le **vrai**
 * compteur Canny ([StairSignal.lineCount], jamais forcé à 0 — le 0 de la voie
 * labels SAM veut dire « non compté », voir [StairWatcher]).
 *
 * Bloqueur connu : seuils Canny calibrés webcam 640×480, à recalibrer sur
 * captures du téléphone (voir `StairDetector`) — le compteur, donc le compte
 * à rebours de la montée, n'est pas fiable avant ce calibrage.
 *
 * Pur JVM, sans OpenCV (doctrine `:core`).
 */
class StairsFeed(
    private val config: StairsConfig = StairsConfig(),
    private val watcher: StairWatcher = StairWatcher(),
    private val holdMs: Long = HOLD_MS,
) {

    /** Dernière phrase confirmée, affichée jusqu'à [shownUntilMs]. */
    private var shown: String = ""

    /** Fin d'affichage de [shown] (0 = rien à afficher). */
    private var shownUntilMs: Long = 0L

    /** Dernier signal Canny (compteur réel, [StairSignal.none] si inexploitable). */
    private var lastSignalValue: StairSignal = StairSignal.none()

    /** Dernière annonce confirmée, `null` si aucune. */
    private var lastCueValue: StairCue? = null

    /** Dernier signal Canny : le compteur réel pour la voix et le journal. */
    val lastSignal: StairSignal get() = lastSignalValue

    /** Dernière annonce confirmée (`null` = aucune). */
    val lastCue: StairCue? get() = lastCueValue

    /**
     * Ligne « escalier » à afficher après cette frame (`""` = rien) : détection
     * sur [luma], confirmation temporelle, maintien de la dernière annonce.
     * [direction] et [centerX] viennent de la voie labels quand elle nomme
     * l'escalier (sens + heure du cadran, `null` = sans heure) ; [meters] est
     * la profondeur du palier quand elle est connue. [onCue] reçoit chaque
     * annonce confirmée (voix douce, `urgent = false` : un escalier est une
     * info de navigation, jamais un stop).
     */
    fun tick(
        luma: ByteArray?,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
        direction: StairDirection = StairDirection.Unknown,
        centerX: Float? = null,
        meters: Float? = null,
        onCue: ((StairCue) -> Unit)? = null,
    ): String {
        val signal = sense(luma)
        lastSignalValue = signal
        val cue = watcher.onSignal(signal, nowMs, language, direction, centerX, meters)
        if (cue != null) {
            lastCueValue = cue
            shown = cue.phrase
            shownUntilMs = nowMs + holdMs
            onCue?.invoke(cue)
        } else if (nowMs >= shownUntilMs) {
            shown = ""
        }
        return shown
    }

    /**
     * Voie voix du flux Canny, miroir de `ProbeWiring.observeStairs` côté
     * labels : même tick, mais l'annonce part vers [speak] (une seule détection
     * par frame, pas de double Canny). Câblage direct :
     * `stairs.observe(luma, now, meters = depth) { cue -> voice(cue.phrase) }`
     * avec `urgent = false` — seule l'urgence (stop) coupe.
     */
    fun observe(
        luma: ByteArray?,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
        direction: StairDirection = StairDirection.Unknown,
        centerX: Float? = null,
        meters: Float? = null,
        speak: (StairCue) -> Unit,
    ): String = tick(luma, nowMs, language, direction, centerX, meters, speak)

    /** Signal Canny de [luma] (plan incomplet = signal vide, sans exception). */
    private fun sense(luma: ByteArray?): StairSignal {
        if (luma == null || luma.size != LumaPlane.SIZE) return StairSignal.none()
        val mask = GrayMask(LumaPlane.WIDTH, LumaPlane.HEIGHT, luma)
        return StairDetector.detect(mask, config)
    }

    /** Oublie l'annonce et l'affichage (nouvelle séance de direct). */
    fun reset() {
        watcher.reset()
        shown = ""
        shownUntilMs = 0L
        lastSignalValue = StairSignal.none()
        lastCueValue = null
    }

    companion object {
        /** Durée d'affichage d'une annonce confirmée. */
        const val HOLD_MS = 3000L
    }
}
