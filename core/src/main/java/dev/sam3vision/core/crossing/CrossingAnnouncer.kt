package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Annonce du mode Traversée : [phrase] à prononcer, [urgent] si elle doit
 * couper le reste (passage au rouge pendant un vert annoncé : la personne
 * est peut-être déjà engagée), [state] l'état annoncé (pastille UI, haptique).
 */
data class CrossingCue(val phrase: String, val urgent: Boolean, val state: CrossingState)

/**
 * Déclencheur du **mode Traversée** : le vert parle aussitôt, le rouge
 * après confirmation (jamais sur un scintillement rouge). Pendant de
 * [dev.sam3vision.core.live.GuidanceAnnouncer], avec l'asymétrie inversée :
 *
 * 1. **le vert parle aussitôt puis répète** — 1 frame suffit (31/10,
 *    ordre user : vert extrêmement facile ; la porte par frame porte seule
 *    la sûreté), puis « Vert, traversez. » redit toutes les
 *    [GREEN_REPEAT_MS] ms tant que le vert dure ;
 * 2. **le rouge parle vite et répète** — [RED_MIN_FRAMES] frames suffisent,
 *    puis « Rouge, attendez. » redit toutes les [RED_REPEAT_MS] ms tant que
 *    le rouge dure : manquer un rouge coûte plus qu'une fausse alerte, et
 *    le silence seul ne retient personne au bord du trottoir (binaire, ordre
 *    user du 31/10 : tout non-vert est rouge, fini le doute) ;
 * 3. **le vert clignotant veto** — deux extinctions sur les [BLINK_WINDOW]
 *    dernières frames annoncent « Vert clignotant, ne pas s'engager. »
 *    (aucun « traversez » tant que ça dure) — urgent si un vert était
 *    annoncé, comme le retour au rouge ;
 * 4. **le retour au rouge coupe tout** — [CrossingCue.urgent] vrai quand le feu
 *    repasse au rouge après un vert annoncé (appelant : couper la lecture du
 *    chat, comme un danger du guidage). Une répétition n'est jamais urgente.
 *
 * Pur et testé en JVM : [atMs] est fourni, aucune horloge implicite.
 */
class CrossingAnnouncer(
    private val greenMinFrames: Int = GREEN_MIN_FRAMES,
    private val redMinFrames: Int = RED_MIN_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val redRepeatMs: Long = RED_REPEAT_MS,
    private val greenRepeatMs: Long = GREEN_REPEAT_MS,
) {

    private var pendingState: CrossingState? = null
    private var pendingCount: Int = 0
    private var announced: CrossingState? = null
    private var lastSpokenAtMs: Long? = null
    private val history = ArrayDeque<CrossingState>()
    private var blinking: Boolean = false

    /** Dernier signal produit (`null` tant que rien n'a été annoncé). */
    var lastCue: CrossingCue? = null
        private set

    /**
     * État observé à [atMs] : le signal à prononcer, ou `null` pour garder le
     * silence. [language] est la langue de la voix (Réglages). Un état déjà
     * annoncé se répète ([redRepeatMs] / [greenRepeatMs], jamais urgent) :
     * en traversée, le feu dit rouge ou vert en continu, jamais muet.
     */
    fun onFrame(
        atMs: Long,
        state: CrossingState,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): CrossingCue? {
        pushHistory(state)
        if (blinkDetected()) return blinkPath(atMs, language)
        blinking = false
        advance(state)
        if (pendingCount < required(state)) return null
        if (state == announced) return repeat(atMs, state, language)
        val last = lastSpokenAtMs
        val interrupt = state == CrossingState.Red && announced == CrossingState.Green
        if (!interrupt && last != null && atMs - last < cooldownMs) return null
        announced = state
        lastSpokenAtMs = atMs
        val cue = CrossingCue(CrossingText.phrase(state, language), interrupt, state)
        lastCue = cue
        return cue
    }

    /**
     * Répétition d'un état déjà annoncé : rouge ou vert, toutes les
     * [redRepeatMs] / [greenRepeatMs] ms, jamais urgent (l'urgence est
     * réservée au retour au rouge après un vert). La répétition parle :
     * elle recule le délai.
     */
    private fun repeat(atMs: Long, state: CrossingState, language: SpeechLanguage): CrossingCue? {
        val interval = if (state == CrossingState.Green) greenRepeatMs else redRepeatMs
        val last = lastSpokenAtMs ?: return null
        if (atMs - last < interval) return null
        lastSpokenAtMs = atMs
        val cue = CrossingCue(CrossingText.phrase(state, language), false, state)
        lastCue = cue
        return cue
    }

    /** Nouvelle session : tout est oublié (le premier état stable sera annoncé). */
    fun reset() {
        pendingState = null
        pendingCount = 0
        announced = null
        lastSpokenAtMs = null
        history.clear()
        blinking = false
        lastCue = null
    }

    /**
     * Clignotant à [atMs] : première détection — annonce (« Vert
     * clignotant, ne pas s'engager. », état rouge fail-safe pour
     * l'affichage et l'haptique), urgente si un vert était annoncé ; puis
     * répétition à la cadence rouge tant que le motif dure. Le vert ne
     * passe plus : veto.
     */
    private fun blinkPath(atMs: Long, language: SpeechLanguage): CrossingCue? {
        if (!blinking) {
            val last = lastSpokenAtMs
            val interrupt = announced == CrossingState.Green
            if (!interrupt && last != null && atMs - last < cooldownMs) return null
            blinking = true
            announced = CrossingState.Red
            lastSpokenAtMs = atMs
            val cue = CrossingCue(CrossingText.blinking(language), interrupt, CrossingState.Red)
            lastCue = cue
            return cue
        }
        val last = lastSpokenAtMs ?: return null
        if (atMs - last < redRepeatMs) return null
        lastSpokenAtMs = atMs
        val cue = CrossingCue(CrossingText.blinking(language), false, CrossingState.Red)
        lastCue = cue
        return cue
    }

    /**
     * Vrai si l'historique montre un vert qui clignote : au moins deux
     * extinctions ([BLINK_TURNS]) sur les [BLINK_WINDOW] dernières frames,
     * avec du vert dedans — une intermittence isolée n'est pas un
     * clignotant.
     */
    private fun blinkDetected(): Boolean {
        if (history.size < BLINK_WINDOW_MIN) return false
        var greens = 0
        var turns = 0
        var previous: CrossingState? = null
        for (state in history) {
            if (state == CrossingState.Green) greens += 1
            if (previous == CrossingState.Green && state != CrossingState.Green) turns += 1
            previous = state
        }
        return greens >= 2 && turns >= BLINK_TURNS
    }

    /** Historique borné des états bruts (clignotant). */
    private fun pushHistory(state: CrossingState) {
        history.addLast(state)
        while (history.size > BLINK_WINDOW) history.removeFirst()
    }

    /** Frames stables exigées : le vert parle aussitôt (1), le rouge se prouve (2). */
    private fun required(state: CrossingState): Int =
        if (state == CrossingState.Green) greenMinFrames else redMinFrames

    /** Compteur de stabilité : le même état vu N frames de suite. */
    private fun advance(state: CrossingState) {
        if (state == pendingState) {
            pendingCount += 1
        } else {
            pendingState = state
            pendingCount = 1
        }
    }

    companion object {
        /**
         * « Traversez » dès la 1re frame verte (31/10, ordre user : vert
         * extrêmement facile — à 2, l'intermittence ratait encore des verts
         * persistants). La porte par frame garde la sûreté ; un vert isolé
         * parle, seul le rouge exige 2 frames.
         */
        const val GREEN_MIN_FRAMES = 1

        /** Frames rouges de suite avant « attendez ». */
        const val RED_MIN_FRAMES = 2

        /** Délai minimal entre deux annonces (le retour au rouge et le clignotant sur vert l'ignorent). */
        const val DEFAULT_COOLDOWN_MS = 2_500L

        /**
         * Répétition du rouge déjà annoncé (31/10/2026, ordre user : le feu
         * dit rouge ou vert toutes les 2 s — le rouge repasse de 1 s à 2 s).
         * La répétition recule le délai comme une annonce normale.
         */
        const val RED_REPEAT_MS = 2_000L

        /**
         * Répétition du vert déjà annoncé (31/10/2026, même ordre : le vert
         * ne reste plus muet, il se redit comme le rouge). Jamais urgente.
         */
        const val GREEN_REPEAT_MS = 2_000L

        /** Fenêtre d'historique du clignotant (frames brutes). */
        const val BLINK_WINDOW = 6

        /** Extinctions sur la fenêtre pour déclarer le clignotant. */
        const val BLINK_TURNS = 2

        /** Historique minimal pour déclarer le clignotant (pas de verdict sur trois frames). */
        const val BLINK_WINDOW_MIN = 4
    }
}
