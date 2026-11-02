package dev.sam3vision.core.exposure

/**
 * Gouverneur d'exposition **sur la boîte du feu** (et non sur la moyenne de la frame) :
 * tant que le crop de lampe est écrasé en blanc ([ClipStats.blown]), il descend la
 * compensation d'exposition d'un cran, puis laisse converger avant de relire. Une lampe
 * occupe 1 à 10 % des pixels d'une frame : la moyenne globale ([ExposureGovernor],
 * `BRIGHT_MEAN = 190`) ne peut pas la voir, cette mesure-là si.
 *
 * Pourquoi c'est sûr : on ne descend **que** vers le plancher (la plage du téléphone lue
 * au binding, resserrée à [MAX_DEPTH] crans), jamais au-dessus de 0, un cran à la fois, et on remonte dès que le
 * crop redevient sain ([EXIT_FRACTION]) — un feu éteint la nuit ne laisse donc pas la
 * caméra sous-exposée. Second garde-fou, mesuré aussi : si la **médiane** du crop passe
 * sous [DARK_MEDIAN], on remonte d'un cran quelle que soit la mesure d'écrêtage, et on
 * refuse de descendre — un feu éteint la nuit n'a pas de raison de payer une
 * sous-exposition qui rend l'image illisible. La remontée est prioritaire sur la
 * descente dans la même frame. La latence vaut une à deux frames de convergence par
 * cran ; une attente de feu rouge dure 20 à 40 s, elle l'absorbe.
 *
 * Pur JVM : aucune API caméra, l'appelant applique l'index via
 * `CameraControl.setExposureCompensationIndex`. Testé en JVM (hystérésis, bornes,
 * stabilisation).
 */
class ClipGovernor(
    /** Borne basse de `exposureCompensationRange` du téléphone (négative ou nulle). */
    val minIndex: Int,
    /** Entrée : au-delà, la lampe est cramée (défaut mesuré, voir [ClipStats.BLOWN_FRACTION]). */
    private val enterFraction: Float = ENTER_FRACTION,
    /** Sortie : en dessous, on rend la main à l'automatisme (hystérésis 3×). */
    private val exitFraction: Float = EXIT_FRACTION,
    /**
     * Plancher de luminosité du crop (canal max médian, 0..255) : en dessous et sous 0,
     * on remonte d'un cran. Mesuré sur les 58 vidéos de rue du 02/11/2026 (voir
     * `~/.scratch/feux-expo/RAPPORT.md`) : un crop de lampe utile garde une médiane très
     * supérieure à ce plancher au repos, et c'est un deux-crans (−2 EV) qui la fait
     * chuter dessous. `0` (ou négatif) désarme la règle.
     */
    private val darkMedian: Int = DARK_MEDIAN,
    /**
     * Profondeur maximale, en crans sous 0 — **plus serrée** que la plage du téléphone
     * quand celle-ci descend loin (−6 EV sur ce Redmi) : une lampe qui n'occupe pas
     * l'image ne doit pas noircir la scène pour SAM. Deux crans couvrent déjà les 4,84 %
     * d'écrasement du pire crop mesuré.
     */
    private val maxDepth: Int = MAX_DEPTH,
) {
    /** Plancher réel : le plus haut des deux (plage du téléphone, profondeur choisie). */
    private val floor: Int = maxOf(minIndex, -maxDepth)

    /** Dernier cran appliqué (ms monotones) : deux crans ne se suivent jamais. */
    private var lastChangeMs: Long = -SETTLE_MS

    /** Dernière frame portant une mesure (ms) : sert au retour au repos. */
    private var lastSignalMs: Long = -RELEASE_MS

    /**
     * Index de compensation voulu après cette frame, ou `null` si [current] convient
     * (ou si le HAL n'offre aucune plage). [stats] `null`/vide = pas de boîte de feu :
     * on ne pilote rien, sauf retour à 0 après [RELEASE_MS] de silence (traversée finie,
     * feu sorti du champ) pour ne pas laisser l'aperçu sombre.
     */
    fun tick(stats: ClipStats?, nowMs: Long, current: Int): Int? {
        if (floor >= 0) return null
        val clamped = current.coerceIn(floor, 0)
        if (nowMs - lastChangeMs < SETTLE_MS) return null
        if (stats == null || stats.total <= 0) {
            if (clamped >= 0 || nowMs - lastSignalMs < RELEASE_MS) return null
            lastChangeMs = nowMs
            return clamped + 1
        }
        lastSignalMs = nowMs
        // Nuit / feu éteint : le crop s'assombrit. On remonte d'un cran, et surtout on ne
        // descend pas — assombrir une image déjà sombre rendrait le feu invisible.
        if (isDark(stats)) {
            if (clamped >= 0) return null
            lastChangeMs = nowMs
            return clamped + 1
        }
        val next = when {
            stats.fraction >= enterFraction && clamped > floor -> clamped - 1
            stats.fraction <= exitFraction && clamped < 0 -> clamped + 1
            clamped != current -> clamped
            else -> return null
        }
        lastChangeMs = nowMs
        return next
    }

    /** Crop plus sombre que [darkMedian] (médiane mesurée, pas le sentinel [ClipStats.UNMEASURED]). */
    private fun isDark(stats: ClipStats): Boolean =
        darkMedian > 0 && stats.median >= 0 && stats.median < darkMedian

    companion object {
        /** Entrée (2,0 % du crop) : les crops sains du dépôt plafonnent à 0,78 %. */
        const val ENTER_FRACTION = 0.02f

        /** Sortie (0,6 % du crop) : sous le pire crop sain, hystérésis d'un facteur 3. */
        const val EXIT_FRACTION = 0.006f

        /** Délai entre deux crans : laisse l'automatisme AE converger (même valeur que l'ancien gouverneur). */
        const val SETTLE_MS = 700L

        /** Deux crans au plus sous 0, quelle que soit la plage du téléphone. */
        const val MAX_DEPTH = 2

        /** Silence toléré sans boîte avant de rendre la main à l'automatisme. */
        const val RELEASE_MS = 3000L

        /**
         * Médiane du crop sous laquelle on refuse de rester sous-exposé (canal max,
         * 0..255) : 51 ≈ 20 % de dynamique. Calibré sur les 12 503 frames de rue du
         * 02/11/2026 — les crops de lampe utiles ont une médiane bien au-dessus, et un
         * −2 EV simulé la fait tomber sous ce plancher dans la majorité des scènes.
         */
        const val DARK_MEDIAN = 51
    }
}
