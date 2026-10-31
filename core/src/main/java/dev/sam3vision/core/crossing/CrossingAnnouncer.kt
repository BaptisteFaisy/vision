package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Annonce du mode Traversée : [phrase] à prononcer, [urgent] si elle doit
 * couper le reste (passage au rouge pendant un vert annoncé : la personne
 * est peut-être déjà engagée), [state] l'état annoncé (pastille UI, haptique).
 */
data class CrossingCue(val phrase: String, val urgent: Boolean, val state: CrossingState)

/**
 * Déclencheur du **mode Traversée** : parle aux changements d'état du feu,
 * jamais sur un scintillement. Pendant de
 * [dev.sam3vision.core.live.GuidanceAnnouncer], avec l'asymétrie inversée :
 *
 * 1. **le vert se confirme puis répète** — [GREEN_MIN_FRAMES] frames vertes
 *    sur les [GREEN_MIN_FRAMES] + 1 dernières (vote temporel : une frame
 *    intercalée — rouge PWM ou doute — ne casse pas la confirmation, deux
 *    si) avant « traversez » (un faux vert engage sur la chaussée, un vert
 *    raté fait juste attendre), puis « Vert, traversez. » redit toutes les
 *    [GREEN_REPEAT_MS] ms tant que le vert dure ;
 * 2. **le rouge parle vite et répète** — [RED_MIN_FRAMES] frames de suite
 *    suffisent, puis « Rouge, attendez. » redit toutes les [RED_REPEAT_MS]
 *    ms tant que le rouge dure : manquer un rouge coûte plus qu'une fausse
 *    alerte, et le silence seul ne retient personne au bord du trottoir ;
 * 3. **le doute fait patienter puis se dit** — [CrossingState.Unknown] ne
 *    casse ni ne fait les confirmations (une frame sans information n'en
 *    est pas une) : le dernier état annoncé persiste [UNKNOWN_TTL_MS] ms,
 *    puis « Feu illisible, attendez. » se dit et se répète toutes les
 *    [UNKNOWN_REPEAT_MS] ms — attendre, en le disant ;
 * 4. **le vert clignotant veto** — deux extinctions sur les [BLINK_WINDOW]
 *    dernières frames annoncent « Vert clignotant, ne pas s'engager. »
 *    (aucun « traversez » tant que ça dure) — urgent si un vert était
 *    annoncé, comme le retour au rouge ;
 * 5. **le retour au rouge coupe tout** — [CrossingCue.urgent] vrai quand le feu
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
    private val unknownMinFrames: Int = UNKNOWN_MIN_FRAMES,
    private val unknownRepeatMs: Long = UNKNOWN_REPEAT_MS,
    private val unknownTtlMs: Long = UNKNOWN_TTL_MS,
) {

    private var pendingState: CrossingState? = null
    private var pendingCount: Int = 0
    private var announced: CrossingState? = null
    private var lastSpokenAtMs: Long? = null
    private val history = ArrayDeque<CrossingState>()
    private var unknownStreak: Int = 0
    private var lastKnownAtMs: Long? = null
    private var blinking: Boolean = false

    /** Dernier signal produit (`null` tant que rien n'a été annoncé). */
    var lastCue: CrossingCue? = null
        private set

    /**
     * État observé à [atMs] : le signal à prononcer, ou `null` pour garder le
     * silence. [language] est la langue de la voix (Réglages). Un état déjà
     * annoncé se répète (rouge, vert ou doute, jamais urgent) : en
     * traversée, le feu se dit en continu, jamais muet.
     */
    fun onFrame(
        atMs: Long,
        state: CrossingState,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): CrossingCue? {
        pushHistory(state)
        if (blinkDetected()) return blinkPath(atMs, language)
        blinking = false
        if (state == CrossingState.Unknown) {
            unknownStreak += 1
            return unknownPath(atMs, language)
        }
        unknownStreak = 0
        lastKnownAtMs = atMs
        advance(state)
        if (!stable(state)) return null
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
     * Répétition d'un état déjà annoncé, toutes les [redRepeatMs] /
     * [greenRepeatMs] / [unknownRepeatMs] ms, jamais urgent (l'urgence est
     * réservée au retour au rouge — ou au clignotant — après un vert). La
     * répétition parle : elle recule le délai.
     */
    private fun repeat(atMs: Long, state: CrossingState, language: SpeechLanguage): CrossingCue? {
        val interval = when (state) {
            CrossingState.Green -> greenRepeatMs
            CrossingState.Red -> redRepeatMs
            CrossingState.Unknown -> unknownRepeatMs
        }
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
        unknownStreak = 0
        lastKnownAtMs = null
        blinking = false
        lastCue = null
    }

    /**
     * Doute à [atMs] : sous [UNKNOWN_TTL_MS] de connu, silence (le dernier
     * annoncé persiste, voix comme affichage) ; passé le délai — ou sans
     * rien d'annoncé — le doute se dit (« Feu illisible, attendez. ») puis
     * se répète. Jamais urgent : on ne coupe pas pour un doute.
     */
    private fun unknownPath(atMs: Long, language: SpeechLanguage): CrossingCue? {
        if (unknownStreak < unknownMinFrames) return null
        val knownAt = lastKnownAtMs
        if (announced != null && knownAt != null && atMs - knownAt < unknownTtlMs) return null
        if (CrossingState.Unknown == announced) return repeat(atMs, CrossingState.Unknown, language)
        val last = lastSpokenAtMs
        if (last != null && atMs - last < cooldownMs) return null
        announced = CrossingState.Unknown
        lastSpokenAtMs = atMs
        val cue = CrossingCue(CrossingText.phrase(CrossingState.Unknown, language), false, CrossingState.Unknown)
        lastCue = cue
        return cue
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
     * avec du vert dedans — une intermittence isolée (PWM, joker du vote)
     * n'est pas un clignotant.
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

    /**
     * Stabilité de [state] : le vert se vote ([greenMinFrames] verts sur les
     * dernières frames, une intercalée tolérée — anti-PWM), le rouge
     * s'enchaîne ([redMinFrames] de suite). Le doute ne passe pas ici (voir
     * [unknownPath]).
     */
    private fun stable(state: CrossingState): Boolean = when (state) {
        CrossingState.Green -> greenVote()
        CrossingState.Red -> pendingState == CrossingState.Red && pendingCount >= redMinFrames
        CrossingState.Unknown -> false
    }

    /** Vote temporel du vert : [greenMinFrames] verts sur les ([greenMinFrames] + 1) dernières. */
    private fun greenVote(): Boolean {
        val window = history.takeLast(greenMinFrames + 1)
        return window.count { it == CrossingState.Green } >= greenMinFrames
    }

    /** Historique borné des états bruts (vote, clignotant). */
    private fun pushHistory(state: CrossingState) {
        history.addLast(state)
        while (history.size > BLINK_WINDOW) history.removeFirst()
    }

    /** Compteur de stabilité : le même état connu vu N frames de suite (le doute le suspend). */
    private fun advance(state: CrossingState) {
        if (state == pendingState) {
            pendingCount += 1
        } else {
            pendingState = state
            pendingCount = 1
        }
    }

    companion object {
        /** Frames vertes (sur les 4 dernières, une intercalée tolérée) avant « traversez » (≈ 0,6 s à 5 ips). */
        const val GREEN_MIN_FRAMES = 3

        /** Frames rouges de suite avant « attendez ». */
        const val RED_MIN_FRAMES = 2

        /** Frames de doute de suite avant « feu illisible » (sous réserve du délai de persistance). */
        const val UNKNOWN_MIN_FRAMES = 2

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

        /** Répétition du doute déjà annoncé, même cadence que le rouge. Jamais urgente. */
        const val UNKNOWN_REPEAT_MS = 2_000L

        /**
         * Persistance du dernier état connu sous le doute : tant qu'un état
         * a été annoncé il y a moins de [UNKNOWN_TTL_MS], les frames
         * illisibles restent muettes (voix comme affichage gardent le
         * dernier annoncé) — passé le délai, le doute se dit. La décision
         * reste fail-safe : seul un vert confirmé fait traverser.
         */
        const val UNKNOWN_TTL_MS = 3_000L

        /** Fenêtre d'historique du clignotant (frames brutes). */
        const val BLINK_WINDOW = 6

        /** Extinctions sur la fenêtre pour déclarer le clignotant. */
        const val BLINK_TURNS = 2

        /** Historique minimal pour déclarer le clignotant (pas de verdict sur trois frames). */
        const val BLINK_WINDOW_MIN = 4
    }
}
