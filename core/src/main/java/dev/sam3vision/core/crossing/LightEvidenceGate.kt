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
 * [Evidence.Unknown] devient [CrossingVerdictLight.Unknown], qui impose
 * « Don't cross. » : c'est le seul cas où l'on ne sait rien.
 *
 * [Evidence.Absent], lui, garde le sens historique de la SPEC v4 : **absence
 * prouvée** (assez de frames sans aucune boîte, contexte de passage) ⇒ aucun
 * feu à cet endroit, donc les voitures tranchent ([CrossingVerdictLight.Absent],
 * `carMoving` et le veto décident). La différence avec [Evidence.Unknown] est
 * exactement celle que ce module existe pour porter : une frame ratée n'est pas
 * une absence, quatre frames concordantes en sont une.
 */
fun Evidence.toVerdictLight(): CrossingVerdictLight = when (this) {
    Evidence.Green -> CrossingVerdictLight.Green
    Evidence.Red -> CrossingVerdictLight.Red
    Evidence.Absent -> CrossingVerdictLight.Absent
    Evidence.Unknown -> CrossingVerdictLight.Unknown
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
 * [reset] — un feu aperçu puis perdu reste [Evidence.Unknown] jusqu'à la borne
 * de ré-armement ci-dessous, jamais « pas de feu ».
 *
 * **Ré-armement (02/11/2026, trou D3)** : la condition « aucun feu vu depuis
 * [reset] » rendait [Evidence.Absent] **inatteignable à vie** dès la première
 * boîte de la session — un feu masqué ou dépassé laissait donc
 * [Evidence.Unknown] pour toujours, d'où un verdict muet pour toujours côté
 * `:app`. L'absence redevient donc **prouvable** après une perte durable :
 * [ABSENT_REARM_FRAMES] frames de suite sans boîte (2 × [ABSENT_MIN_FRAMES] =
 * 8, soit 0,7 à 1,6 s à 5-12 ips) rouvrent la conclusion. Rien ne se conclut
 * plus vite qu'avant ; on cesse seulement de se taire à vie.
 *
 * Côté couleur, [Evidence.Green] exige [MIN_GREEN_EVIDENCE_FRAMES] crops
 * concordants **et** [GREEN_HOLD_MS] de vert continu. Ces deux preuves sont
 * **neutralisées par le câblage de production** : la confirmation du vert est
 * le vote 3/5 mesuré A5 (voir les KDoc des deux constantes).
 *
 * Invariant tenu par construction et vérifié par les tests :
 * **`Unknown` et `Absent` ne sont jamais `Green`**. En particulier, un
 * `Absent` ne doit jamais produire « Cross. ».
 *
 * Pur et testé en JVM : [onFrame] reçoit [atMs] (horodatage du flux de la
 * lentille), aucune horloge implicite, aucun état global.
 */
class LightEvidenceGate(
    /**
     * Crops verts concordants exigés : **neutralisée par le câblage de
     * production** (`CrossingVerdictWiring` construit la porte avec `1`), où la
     * confirmation du vert est le vote 3/5 de `LightTemporalVote` (mesure A5 :
     * 371/400 verts, 16 faux verts, +180 ms p50). Deux confirmations
     * empileraient deux délais (A9 : +0,8 à 1,5 s). Voir
     * [MIN_GREEN_EVIDENCE_FRAMES].
     */
    private val minGreenFrames: Int = MIN_GREEN_EVIDENCE_FRAMES,
    /**
     * Tenue minimale du vert en ms : même statut que [minGreenFrames], le
     * câblage de production passe `0L` et la tenue vient du vote (3 verts sur
     * les 5 dernières frames à boîte). Voir [GREEN_HOLD_MS].
     */
    private val greenHoldMs: Long = GREEN_HOLD_MS,
    private val absentMinFrames: Int = ABSENT_MIN_FRAMES,
) {

    /** Frames de suite sans boîte qui rouvrent la conclusion d'absence (2 × [absentMinFrames]). */
    private val absentRearmFrames: Int = absentMinFrames * ABSENT_REARM_FACTOR

    /** Vert continu du segment courant, en frames concordantes. */
    private var greenFrames = 0

    /** Début du segment vert courant (`null` = pas de vert en cours). */
    private var greenSinceMs: Long? = null

    /** Frames de suite sans aucune boîte (remis à zéro dès qu'une boîte revient). */
    private var absentFrames = 0

    /** Un feu a-t-il déjà été vu depuis le dernier [reset] ? Remis à faux par [ABSENT_REARM_FRAMES] frames de perte durable. */
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
        // Perte durable : l'absence redevient prouvable, sinon elle ne l'est
        // plus jamais après la première boîte de la session (trou D3).
        if (absentFrames >= absentRearmFrames) sawAnyBox = false
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
         *
         * **Neutralisé par le câblage de production** (`CrossingVerdictWiring`
         * construit la porte avec `minGreenFrames = 1`) : la confirmation du
         * vert y est le **vote 3/5** de `LightTemporalVote` (mesure A5 :
         * 371/400 verts trouvés, 16 faux verts sur 980 séquences, +180 ms p50).
         * Garder les deux aurait additionné deux délais de confirmation, dont
         * +0,8 à 1,5 s mesurés en A9. La constante ne sert donc qu'aux appelants
         * sans vote (bancs, tests du contrat de la porte).
         */
        const val MIN_GREEN_EVIDENCE_FRAMES = 2

        /**
         * Vert **continu** exigé avant de croire au vert : 700 ms.
         *
         * **Neutralisé par le câblage de production** (`greenHoldMs = 0L`, même
         * appel que [MIN_GREEN_EVIDENCE_FRAMES]) : la tenue est portée par le
         * vote 3/5 (3 frames vertes sur les 5 dernières à boîte, soit 300-400 ms
         * à 12-15 fps, mesure A5) puis par l'anti-clignotant du vote
         * (`EXIT_FRAMES = 2`). Un vert piéton dure ≥ 5 s en France ; le
         * clignotant, lui, ne passe pas 3 frames sur 5.
         */
        const val GREEN_HOLD_MS = 700L

        /**
         * Frames de suite sans aucune boîte avant de conclure à l'absence
         * **prouvée** (4 ≈ 0,8 s à 5 ips, même valeur que
         * `NoLightGate.NO_LIGHT_MIN_FRAMES` : un faux « pas de feu » envoie sur
         * la chaussée, il se prouve comme un vert).
         */
        const val ABSENT_MIN_FRAMES = 4

        /**
         * Facteur de **ré-armement** de la preuve d'absence : une boîte vue ne
         * condamne plus la porte au silence, [ABSENT_REARM_FRAMES] frames sans
         * boîte rendent [Evidence.Absent] de nouveau concluable (trou D3 :
         * `Absent` inatteignable à vie ⇒ verdict muet à vie).
         */
        const val ABSENT_REARM_FACTOR = 2

        /**
         * Frames de suite sans boîte qui rouvrent la conclusion d'absence :
         * 2 × [ABSENT_MIN_FRAMES] = **8** frames, soit 0,7 à 1,6 s à 5-12 ips.
         * C'est la borne de silence du verdict (`:app` l'utilise aussi comme
         * délai de déblocage) : au-delà, on ne se tait plus, on parle.
         */
        const val ABSENT_REARM_FRAMES = ABSENT_MIN_FRAMES * ABSENT_REARM_FACTOR
    }
}
