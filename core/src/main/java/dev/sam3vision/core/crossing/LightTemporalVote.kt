package dev.sam3vision.core.crossing

/**
 * Vote temporel glissant sur la couleur du feu piéton : la décision de
 * traversée n'est plus prise **par frame** mais sur les [window] dernières
 * frames, pour qu'une frame bruitée (reflet, piéton qui passe, feu voisin
 * entré dans le crop) ne bascule plus le verdict à elle seule.
 *
 * Règle **asymétrique assumée** (ordre user du 31/10 : tout non-vert attend) :
 *
 * - **Vert** = preuve forte : au moins [needGreen] frames qui prouvent le vert
 *   parmi les [window] dernières (accord **glissant**, pas forcément
 *   consécutif — un intrus isolé ne remet pas le compteur à zéro, mesuré
 *   §3.3d : la version consécutive perd 53 verts sur 400 en FR et 70 de plus
 *   en PTL).
 * - **Rouge** = peu coûteux : une frame de rouge **prouvé** (le booléen
 *   `redProven`, c'est-à-dire une fraction rouge ≥ [PedLightGate.RED_MAX_FRACTION]
 *   = 0,02 — même constante que la porte, un vert n'en produit jamais) sort
 *   immédiatement et **vide** la fenêtre ; sinon [exitFrames] frames non
 *   vertes consécutives suffisent (anti-clignotement de la parole).
 *
 * Mesuré (banc `feux-improve/a5`, 980 séquences de 5-12 frames sur les 49
 * crops téléphone FR étiquetés, 300-600 ms, gigue de cadrage + flou de bougé
 * + exposition + intrus) :
 *
 * | règle | verts trouvés | faux verts | latence verte p50 |
 * |---|---|---|---|
 * | une frame (master) | 393/400 | 107 (18,4 %) | 57 ms |
 * | vote majoritaire k=3 | 385/400 | 43 (7,4 %) | 162 ms |
 * | vote majoritaire k=5 | 371/400 | 16 (2,8 %) | 264 ms |
 * | **ce vote 3/5** | **371/400** | **16 (2,8 %)** | **180 ms** |
 *
 * Même preuve que le vote majoritaire k=5, mais sans attendre la fenêtre
 * pleine : on annonce dès que l'accord existe (−84 ms). Sortie par défaut à
 * [EXIT_FRAMES] = 2 : 37 bascules V→R au lieu de 201 à 1 frame, pour un p90
 * de sortie de 53 ms. Détail et table Pareto : `docs/MESURES-FEU-TEMPOREL.md`.
 *
 * L'API est **volontairement découplée des pixels** : elle ne prend qu'un
 * [CrossingState] (la couleur déjà décidée par [PedLightGate.verdict]) et le
 * booléen `redProven`. `:core` ne relit donc aucun pixel ici, et `:app` peut
 * brancher le vote sans exposer d'état interne : seul le verdict rendu par
 * [onFrame] compte. État gardé : [window] booléens (anneau), l'index
 * d'écriture, le compte de preuves vertes, le compteur de frames non vertes
 * consécutives et l'état courant — **aucune horloge, aucune allocation par
 * frame**, coût O(1), déterministe.
 *
 * Usage (`app/.../isee/CrossingVerdictWiring.kt`, une instance par
 * `CrossingVerdictEngine`, remise à zéro par `CrossingVerdictEngine.reset()` à
 * l'activation du mode) :
 * ```
 * val vote = LightTemporalVote()               // 3 verts sur 5 frames
 * val decided = vote.onFrame(rawState, LightTemporalVote.redProven(redFraction))
 * ```
 * [reset] à l'entrée du mode Traversée et quand le feu suivi change (mêmes
 * points que `CrossingAnnouncer.reset()` et `CrossingLightDue.reset()`), pour
 * qu'aucune preuve d'avant la session ne survive.
 */
class LightTemporalVote(
    private val window: Int = WINDOW_FRAMES,
    private val needGreen: Int = NEED_GREEN_FRAMES,
    private val exitFrames: Int = EXIT_FRAMES,
) {

    init {
        require(window >= 1) { "fenêtre vide" }
        require(needGreen in 1..window) { "preuve verte hors fenêtre" }
        require(exitFrames >= 1) { "sortie sans frame" }
    }

    private val greenProof = BooleanArray(window)
    private var cursor = 0
    private var greenInWindow = 0
    private var misses = 0

    /** Verdict courant : [CrossingState.Red] tant que le vert n'est pas prouvé. */
    var state: CrossingState = CrossingState.Red
        private set

    /**
     * Une frame de plus : [rawState] est la couleur de cette frame seule
     * ([PedLightGate.verdict]) et [redProven] dit si elle porte un rouge
     * franc ([redProven]). Rend le verdict **après** accumulation, qui seul
     * doit être annoncé.
     */
    fun onFrame(rawState: CrossingState, redProven: Boolean): CrossingState {
        if (redProven) {
            forget()
            state = CrossingState.Red
            return state
        }
        val provesGreen = rawState == CrossingState.Green
        push(provesGreen)
        if (state == CrossingState.Green) {
            if (misses >= exitFrames) {
                forget()
                state = CrossingState.Red
            }
        } else if (greenInWindow >= needGreen) {
            state = CrossingState.Green
        }
        return state
    }

    /** Nouveau feu, nouvelle session : rien n'est prouvé, le prochain vert repart de zéro. */
    fun reset() {
        forget()
        state = CrossingState.Red
    }

    /** Écrit la preuve de la frame dans l'anneau et tient les deux compteurs. */
    private fun push(provesGreen: Boolean) {
        if (greenProof[cursor]) greenInWindow -= 1
        greenProof[cursor] = provesGreen
        if (provesGreen) greenInWindow += 1
        cursor = (cursor + 1) % window
        misses = if (provesGreen) 0 else misses + 1
    }

    /** Vide la preuve accumulée : une sortie du rouge ne se rejoue pas depuis les frames d'avant. */
    private fun forget() {
        greenProof.fill(false)
        greenInWindow = 0
        cursor = 0
        misses = 0
    }

    companion object {
        /** Preuves vertes exigées dans la fenêtre : 3 frames concordantes. */
        const val NEED_GREEN_FRAMES = 3

        /** Fenêtre d'accord glissant : 5 frames (≈ 300-400 ms à 12-15 fps). */
        const val WINDOW_FRAMES = 5

        /** Frames non vertes consécutives avant de retirer un vert (2 ≈ 130-160 ms). */
        const val EXIT_FRAMES = 2

        /**
         * Un rouge est **prouvé** dès [PedLightGate.RED_MAX_FRACTION] de pixels
         * rouges francs dans le crop (0,02) : la même constante que la porte,
         * donc aucun vert admis par la porte ne peut la franchir. Le seuil vit
         * en un seul endroit ; l'appelant n'a que la fraction à fournir.
         */
        fun redProven(redFraction: Float): Boolean = redFraction >= PedLightGate.RED_MAX_FRACTION
    }
}
