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
 * 1. **le vert se confirme** — [GREEN_MIN_FRAMES] frames vertes de suite avant
 *    « traversez » (un faux vert engage sur la chaussée, un vert raté fait
 *    juste attendre) ;
 * 2. **le rouge parle vite** — [RED_MIN_FRAMES] frames suffisent : manquer un
 *    rouge coûte plus qu'une fausse alerte ;
 * 3. **le doute parle une fois** — comme le rouge, pour dire pourquoi on ne
 *    traverse pas, puis se tait jusqu'au prochain changement d'état ;
 * 4. **le retour au rouge coupe tout** — [CrossingCue.urgent] vrai quand le feu
 *    repasse au rouge après un vert annoncé (appelant : couper la lecture du
 *    chat, comme un danger du guidage).
 *
 * Pur et testé en JVM : [atMs] est fourni, aucune horloge implicite.
 */
class CrossingAnnouncer(
    private val greenMinFrames: Int = GREEN_MIN_FRAMES,
    private val redMinFrames: Int = RED_MIN_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
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
     * silence. [language] est la langue de la voix (Réglages).
     */
    fun onFrame(
        atMs: Long,
        state: CrossingState,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): CrossingCue? {
        advance(state)
        if (pendingCount < required(state) || state == announced) return null
        val last = lastSpokenAtMs
        val interrupt = state == CrossingState.Red && announced == CrossingState.Green
        if (!interrupt && last != null && atMs - last < cooldownMs) return null
        announced = state
        lastSpokenAtMs = atMs
        val cue = CrossingCue(CrossingText.phrase(state, language), interrupt, state)
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

    /** Frames stables exigées : le vert se prouve, le rouge et le doute moins. */
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
        /** Frames vertes de suite avant « traversez » (≈ 0,8 s à 5 ips). */
        const val GREEN_MIN_FRAMES = 4

        /** Frames rouges (ou douteuses) de suite avant « attendez ». */
        const val RED_MIN_FRAMES = 2

        /** Délai minimal entre deux annonces (le retour au rouge l'ignore). */
        const val DEFAULT_COOLDOWN_MS = 2_500L
    }
}
