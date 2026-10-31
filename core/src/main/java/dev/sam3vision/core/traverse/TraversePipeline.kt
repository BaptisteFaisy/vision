package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Sortie d'une frame traversée : objets filtrés, détections converties, décision
 * du veto, signal vocal (`null` = silence) et bilan voix (`null` = rien à dire
 * ou voix absente).
 */
data class TraverseOutput(
    val objects: List<SegObject>,
    val detections: List<Detection>,
    val decision: TraverseDecision,
    val cue: CrossingCue?,
    val voice: TraverseVoiceOutcome?,
)

/**
 * Pipeline du mode traversée : hystérésis → veto → annonce → voix.
 *
 * 1. [TraverseState] anti-scintillement sur les objets SAM ;
 * 2. conversion en [Detection] (mêmes dimensions que l'appelant direct) ;
 * 3. [TraverseVeto] : un véhicule qui approche ou proche force « attendez » ;
 * 4. [CrossingAnnouncer] : le signal vocal, confirmé sur plusieurs frames —
 *    [TraversePhase.PRET] et [TraversePhase.TRAVERSER] valent vert, `ATTENDRE`
 *    et `ALERTE` valent rouge (binaire, voir [CrossingAnnouncer]) ;
 * 5. [TraverseVoice.speak] avec `cue.phrase` et `cue.urgent` : le retour au
 *    rouge après un vert annoncé coupe la lecture en cours.
 *
 * [lightGreen] est le vert **confirmé** du module feu (`false` par défaut :
 * sans vert, « attendez » — le pipeline ne dit jamais « traversez » tout seul).
 * [voice] `null` = pas de voix (filtrage + veto seuls) ; l'appelant l'attache
 * quand le mode traversée est actif.
 *
 * Pur Kotlin/JVM (la voix parle derrière ses ports moqués) ; le câblage
 * `:app` (lecteur, TTS système, cache) vit dans `traverse/` côté app.
 */
class TraversePipeline(
    private val state: TraverseState = TraverseState(),
    private val veto: TraverseVeto = TraverseVeto(),
    private val announcer: CrossingAnnouncer = CrossingAnnouncer(),
    /** Voix du mode, attachée par l'appelant (`null` = muet). */
    @Volatile var voice: TraverseVoice? = null,
) {

    /**
     * Traite une frame : [objects] filtrés, convertis en [width]×[height],
     * soumis au veto ([focalPx] ≤ 0 : distance indisponible, seule
     * l'approche compte), annoncés puis parlés à [atMs].
     */
    suspend fun onFrame(
        objects: List<SegObject>,
        width: Int,
        height: Int,
        lightGreen: Boolean,
        focalPx: Float,
        atMs: Long,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): TraverseOutput {
        val filtered = state.update(objects)
        val detections = filtered.toDetections(width, height)
        val decision = veto.update(lightGreen, detections, focalPx, atMs)
        val cue = announcer.onFrame(atMs, decision.crossingState(), language)
        val outcome = speak(cue)
        return TraverseOutput(filtered, detections, decision, cue, outcome)
    }

    /** Oublie pistes, veto et annonces (nouvelle traversée, nouvelle séance). */
    fun reset() {
        state.reset()
        veto.reset()
        announcer.reset()
    }

    /** Prononce [cue] (`null` = silence, voix absente = signal seul). */
    private suspend fun speak(cue: CrossingCue?): TraverseVoiceOutcome? {
        if (cue == null) return null
        return voice?.speak(cue.phrase, cue.urgent)
    }

    companion object {
        /**
         * Prompts du mode traversée : véhicules d'abord (voir
         * [VehicleConcepts]), puis bordure et trottoir (le bord et le but se
         * suivent en traversée), [base] ensuite, bornés au plafond du direct
         * ([LivePrompts.MAX] : 3 véhicules + 2 + base, le tout borné à 16).
         */
        fun promptsFor(base: List<String> = emptyList()): List<String> =
            VehicleConcepts.promptsFor(listOf("curb", "sidewalk") + base)
    }
}

/**
 * Phase du veto vue par l'annonceur : vert confirmé ou maintenu = vert,
 * attente ou alerte = rouge (binaire). Publique : la sonde iSee y lit le
 * même verdict que le direct.
 */
fun TraverseDecision.crossingState(): CrossingState = when (state) {
    TraversePhase.PRET, TraversePhase.TRAVERSER -> CrossingState.Green
    TraversePhase.ATTENDRE, TraversePhase.ALERTE -> CrossingState.Red
}
