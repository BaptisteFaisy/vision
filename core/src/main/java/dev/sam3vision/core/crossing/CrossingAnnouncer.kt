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
 *    de suite avant « traversez » (un faux vert engage sur la chaussée, un
 *    vert raté fait juste attendre), puis « Vert, traversez. » redit toutes
 *    les [GREEN_REPEAT_MS] ms tant que le vert dure ;
 * 2. **le rouge parle vite et répète** — [RED_MIN_FRAMES] frames suffisent,
 *    puis « Rouge, attendez. » redit toutes les [RED_REPEAT_MS] ms tant que
 *    le rouge dure : manquer un rouge coûte plus qu'une fausse alerte, et
 *    le silence seul ne retient personne au bord du trottoir (binaire, ordre
 *    user du 31/10 : tout non-vert est rouge, fini le doute) ;
 * 3. **le retour au rouge coupe tout** — [CrossingCue.urgent] vrai quand le feu
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
        lastCue = null
    }

    /** Frames stables exigées : le vert comme le rouge se prouvent en 2 frames. */
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
         * Frames vertes de suite avant « traversez » (≈ 0,4 s à 5 ips).
         * 2 depuis le 31/10 (ordre user : à 4, P(annonce) = p⁴ et le vert
         * n'était jamais annoncé) ; la porte par frame garde la sûreté, un
         * vert isolé reste muet.
         */
        const val GREEN_MIN_FRAMES = 2

        /** Frames rouges de suite avant « attendez ». */
        const val RED_MIN_FRAMES = 2

        /** Délai minimal entre deux annonces (le retour au rouge l'ignore). */
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
    }
}
