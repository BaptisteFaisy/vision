package dev.sam3vision.core.crossing

/**
 * Preuve lumineuse rendue par [LightEvidenceGate] pour une frame : ce que la
 * vision a **réellement** vu, pas ce qu'on espère.
 *
 * - [Green] : couleur verte lue, confirmée (crops concordants + tenue minimale).
 * - [Red] : couleur rouge lue — parlante tout de suite.
 * - [Unknown] : **aucune preuve lisible** (boîte manquante sur cette frame,
 *   boîte vue mais couleur illisible, vert pas encore confirmé, alternance).
 * - [Absent] : absence **prouvée** (assez de frames sans aucune boîte **et** un
 *   contexte qui permet de conclure, par exemple l'utilisateur est bien face au
 *   passage). Ce n'est pas une couleur : c'est une constatation d'absence.
 *
 * Règle produit : seule [Green] autorise « Cross. ». `Absent` n'est jamais une
 * autorisation de traverser par le chemin du feu ; `Absent` ne doit jamais
 * produire « Cross. » (mesure A9, trou D1 : boîte nulle ⇒ autorisation).
 */
enum class Evidence {

    Green,
    Red,
    Unknown,
    Absent;

    /**
     * Vrai seulement pour [Green] : la seule valeur qui autorise « Cross. ».
     * `Unknown` et `Absent` valent **jamais** vert.
     */
    val authorizesCrossing: Boolean
        get() = this == Green

    /** Vrai si une couleur a été lue sur un feu ([Green] ou [Red]). */
    val readable: Boolean
        get() = this == Green || this == Red
}

/**
 * Traduction vers le contrat du verdict ([CrossingVerdictRule.decide]) :
 * [Evidence.Unknown] **et** [Evidence.Absent] deviennent
 * [CrossingVerdictLight.Unknown], qui impose « Don't cross. ».
 *
 * Pourquoi [Evidence.Absent] aussi : le chemin du feu ne sait rien prouver
 * d'autre que « je ne vois pas de feu vert ». Une traversée **sans feu** reste
 * possible, mais par une autre porte (`NoLightGate` / `NoLightAnnouncer`), qui
 * exige sa propre preuve (aucun feu depuis plusieurs frames, calme continu) et
 * parle sa propre phrase — jamais un vert emprunté.
 */
fun Evidence.toVerdictLight(): CrossingVerdictLight = when (this) {
    Evidence.Green -> CrossingVerdictLight.Green
    Evidence.Red -> CrossingVerdictLight.Red
    Evidence.Unknown, Evidence.Absent -> CrossingVerdictLight.Unknown
}

/**
 * Porte de preuve lumineuse : distingue l'absence **prouvée** de l'absence de
 * preuve, pour que « je n'ai pas vu de feu » ne parte plus jamais en
 * « Cross. » (mesure A9, §4.2 / trou D1).
 *
 * Une boîte manquante n'est **pas** une preuve : détection ratée, camion qui
 * masque, téléphone baissé, lentille sale donnent tous `box == null`. La porte
 * ne conclut donc [Evidence.Absent] qu'après [ABSENT_MIN_FRAMES] frames de
 * suite sans aucune boîte, **et** si le contexte autorise la conclusion
 * ([facingCrossing]), **et** si aucun feu n'a été vu depuis le dernier
 * [reset] — un feu aperçu puis perdu reste [Evidence.Unknown], jamais « pas de
 * feu ».
 *
 * Côté couleur, [Evidence.Green] exige deux preuves indépendantes, comme la
 * mesure l'impose : [MIN_GREEN_EVIDENCE_FRAMES] crops concordants **et**
 * [GREEN_HOLD_MS] de vert continu. Un vert d'une frame est un artefact de
 * clignotant ou de scène ; un feu piéton vert dure ≥ 5 s en France.
 *
 * Invariant tenu par construction et vérifié par les tests :
 * **`Unknown` et `Absent` ne sont jamais `Green`**. En particulier, un
 * `Absent` ne doit jamais produire « Cross. ».
 *
 * Pur et testé en JVM : [onFrame] reçoit [atMs] (horodatage du flux de la
 * lentille), aucune horloge implicite, aucun état global.
 */
class LightEvidenceGate(
    private val minGreenFrames: Int = MIN_GREEN_EVIDENCE_FRAMES,
    private val greenHoldMs: Long = GREEN_HOLD_MS,
    private val absentMinFrames: Int = ABSENT_MIN_FRAMES,
) {

    /** Vert continu du segment courant, en frames concordantes. */
    private var greenFrames = 0

    /** Début du segment vert courant (`null` = pas de vert en cours). */
    private var greenSinceMs: Long? = null

    /** Frames de suite sans aucune boîte (remis à zéro dès qu'une boîte revient). */
    private var absentFrames = 0

    /** Un feu a-t-il déjà été vu depuis le dernier [reset] ? */
    private var sawAnyBox = false

    /** Dernière preuve rendue ([Evidence.Unknown] avant la première frame). */
    var last: Evidence = Evidence.Unknown
        private set

    /**
     * Avance d'une frame et rend la preuve : [sawAnyLightBox] vrai si la vision
     * a rendu **au moins une** boîte de feu sur cette frame, [bestState] la
     * couleur lue sur la meilleure boîte (`null` = boîte vue, couleur illisible),
     * [facingCrossing] le contexte (l'utilisateur est-il bien face au passage —
     * sans lui, aucune absence ne peut être conclue), [atMs] l'horodatage du flux.
     */
    fun onFrame(
        atMs: Long,
        sawAnyLightBox: Boolean,
        bestState: CrossingState?,
        facingCrossing: Boolean = true,
    ): Evidence {
        val evidence = if (sawAnyLightBox) withBox(atMs, bestState) else withoutBox(facingCrossing)
        last = evidence
        return evidence
    }

    /** Frame avec au moins une boîte : la couleur tranche, ou rien n'est lisible. */
    private fun withBox(atMs: Long, bestState: CrossingState?): Evidence {
        sawAnyBox = true
        absentFrames = 0
        return when (bestState) {
            null -> unreadable()
            CrossingState.Red -> red()
            CrossingState.Green -> green(atMs)
        }
    }

    /** Frame sans boîte : absence prouvée seulement après assez de frames et un contexte concluant. */
    private fun withoutBox(facingCrossing: Boolean): Evidence {
        forgetGreen()
        absentFrames += 1
        val proven = absentFrames >= absentMinFrames && facingCrossing && !sawAnyBox
        return if (proven) Evidence.Absent else Evidence.Unknown
    }

    /** Boîte vue, couleur illisible : rien à conclure, et le vert en cours est rompu. */
    private fun unreadable(): Evidence {
        forgetGreen()
        return Evidence.Unknown
    }

    /** Rouge lu : immédiat (contredire vite est sûr), et le vert en cours est rompu. */
    private fun red(): Evidence {
        forgetGreen()
        return Evidence.Red
    }

    /** Vert lu : confirmé seulement après concordance **et** tenue minimale. */
    private fun green(atMs: Long): Evidence {
        val since = greenSinceMs
        if (since == null) {
            greenSinceMs = atMs
            greenFrames = 1
        } else {
            greenFrames += 1
        }
        val opened = greenSinceMs ?: atMs
        val held = atMs - opened >= greenHoldMs
        return if (greenFrames >= minGreenFrames && held) Evidence.Green else Evidence.Unknown
    }

    /** Rompt le segment vert courant (toute frame non verte le fait). */
    private fun forgetGreen() {
        greenFrames = 0
        greenSinceMs = null
    }

    /** Oublie tout (nouvelle traversée, nouvelle séance) : première frame comme au démarrage. */
    fun reset() {
        forgetGreen()
        absentFrames = 0
        sawAnyBox = false
        last = Evidence.Unknown
    }

    companion object {
        /**
         * Crops verts **concordants** exigés avant de croire au vert (2, comme
         * `NoLightAnnouncer.CROSS_MIN_FRAMES`) : un état isolé est du bruit.
         */
        const val MIN_GREEN_EVIDENCE_FRAMES = 2

        /**
         * Vert **continu** exigé avant de croire au vert : 700 ms.
         *
         * **Provisoire, à calibrer en rue.** Un vert piéton dure ≥ 5 s en
         * France ; un vert de 200 ms est un artefact de clignotant ou de scène
         * (le mode lunettes tourne à 24 ips, donc 200 ms y font 5 frames : le
         * seul compte de frames ne suffit pas à écarter l'artefact). Coût
         * mesuré sur les ancres (A9) : 0,8 à 1,5 s de délai sur « Cross. ».
         */
        const val GREEN_HOLD_MS = 700L

        /**
         * Frames de suite sans aucune boîte avant de conclure à l'absence
         * **prouvée** (4 ≈ 0,8 s à 5 ips, même valeur que
         * `NoLightGate.NO_LIGHT_MIN_FRAMES` : un faux « pas de feu » envoie sur
         * la chaussée, il se prouve comme un vert).
         */
        const val ABSENT_MIN_FRAMES = 4
    }
}
