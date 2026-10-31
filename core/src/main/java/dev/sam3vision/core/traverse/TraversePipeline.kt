package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.mask.toDetectionsWithExtents
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject
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
    /**
     * Sonde fond V1 (voiture-bouge), attachée par l'appelant (`null` = coupée) :
     * verdict log-only dans [lastBackgroundMoving], le veto n'en dépend pas.
     */
    @Volatile var backgroundProbe: BackgroundMotionProbe? = null,
) {

    /** Dernière luminance reçue (`null` = aucune) : fait le prev de la prochaine frame. */
    private var lastBackground: BackgroundFrame? = null

    /** Dernier verdict fond V1 (`null` = pas de paire prev/cur ou sonde coupée), log-only. */
    var lastBackgroundMoving: Boolean? = null
        private set

    /**
     * Traite une frame : [objects] filtrés, convertis en [width]×[height],
     * soumis au veto ([focalPx] ≤ 0 : distance indisponible, seule
     * l'approche compte), annoncés puis parlés à [atMs]. [background] alimente
     * la sonde fond V1 (verdict log-only, veto inchangé).
     */
    suspend fun onFrame(
        objects: List<SegObject>,
        width: Int,
        height: Int,
        lightGreen: Boolean,
        focalPx: Float,
        atMs: Long,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        background: BackgroundFrame? = null,
    ): TraverseOutput {
        val filtered = state.update(objects)
        // Extrêmes masque inclus : le veto lit `.box` (inchangé), le direct mesure
        // au plus juste (voir `Detection.maskBox`).
        val detections = filtered.toDetectionsWithExtents(width, height)
        val decision = veto.update(lightGreen, detections, focalPx, atMs)
        noteBackground(background, detections)
        val cue = announcer.onFrame(atMs, decision.crossingState(), language)
        val outcome = speak(cue)
        return TraverseOutput(filtered, detections, decision, cue, outcome)
    }

    /**
     * Dernier état annoncé (`null` tant que rien n'est annoncé) : l'affichage
     * direct suit la voix, jamais la lecture brute — un glitch d'une frame ne
     * se voit pas (ordre user du 31/10, certitude rouge/vert).
     */
    val lastAnnounced: CrossingState? get() = announcer.lastCue?.state

    /** Oublie pistes, veto, annonces et paire fond (nouvelle traversée, nouvelle séance). */
    fun reset() {
        state.reset()
        veto.reset()
        announcer.reset()
        lastBackground = null
        lastBackgroundMoving = null
    }

    /** Prononce [cue] (`null` = silence, voix absente = signal seul). */
    private suspend fun speak(cue: CrossingCue?): TraverseVoiceOutcome? {
        if (cue == null) return null
        return voice?.speak(cue.phrase, cue.urgent)
    }

    /**
     * Sonde fond V1 : paire [cur] au prev, `decide()` quand la paire est complète
     * et la sonde attachée — verdict dans [lastBackgroundMoving], log-only (le
     * veto n'en dépend pas). Dimensions différentes = paire jetée (recadrage).
     */
    private fun noteBackground(cur: BackgroundFrame?, detections: List<Detection>) {
        val prev = lastBackground
        lastBackground = cur
        val probe = backgroundProbe ?: return
        if (prev == null || cur == null) return
        if (prev.width != cur.width || prev.height != cur.height) return
        lastBackgroundMoving = probe.decide(prev, cur, vehicleBoxes(detections))
    }

    /** Boîtes des véhicules de la frame, tous scores (la sonde fond compare hors véhicules). */
    private fun vehicleBoxes(detections: List<Detection>): List<List<Float>> =
        detections.filter { VehicleConcepts.isVehicle(it.label) }.map { it.box }

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
 * Luminance d'une frame pour la sonde fond V1 : [width]×[height], plan Y 0..255
 * en ligne majeure. Le fond seul décide : fond qui change ⇒ voiture qui bouge.
 */
data class BackgroundFrame(val width: Int, val height: Int, val luma: ByteArray)

/**
 * Sonde fond V1 (voiture-bouge) : [decide] compare le fond hors [boxes]
 * (véhicules, boîtes proto `x1,y1,x2,y2`) entre [prev] et [cur] (mêmes
 * dimensions) — `true` = fond qui change = voiture qui bouge, `false` = fond
 * identique (une bbox qui bouge seule vaut caméra qui bouge, pas veto).
 */
fun interface BackgroundMotionProbe {
    fun decide(prev: BackgroundFrame, cur: BackgroundFrame, boxes: List<List<Float>>): Boolean
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
