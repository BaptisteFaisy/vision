package dev.sam3vision.core.stairs

/**
 * Seuils du détecteur d'escaliers classique, adapté de `config.py` (§5) de
 * `github.com/agrimbillowria/blind-project` (licence MIT, voir `NOTICE` racine).
 *
 * Les quatre premiers seuils sont les valeurs MIT d'origine, calibrées sur une
 * webcam 640×480 ; les trois derniers n'existent que dans la variante
 * par projection (pas d'équivalent Hough) et restent à recalibrer sur des
 * captures du téléphone (TODO câblage : `StairDetector`).
 */
data class StairsConfig(
    /** Seuil bas de Canny (MIT : `STAIR_CANNY_LOW`). */
    val cannyLow: Int = 50,
    /** Seuil haut de Canny (MIT : `STAIR_CANNY_HIGH`). */
    val cannyHigh: Int = 150,
    /** Écart à l'horizontale encore compté (MIT : `STAIR_ANGLE_TOLERANCE`, degrés). */
    val angleToleranceDeg: Int = 15,
    /** Lignes quasi-horizontales pour conclure « escalier » (MIT : `STAIR_MIN_LINES`). */
    val minLines: Int = 4,
    /** Haut de la ROI en fraction de la hauteur (MIT : bas 60 %, donc 0,4). */
    val roiTopFraction: Float = 0.4f,
    /**
     * Couverture minimale d'une ligne de l'image pour compter comme marche :
     * 0,3 = 30 % de la largeur, miroir du `minLineLength = w * 0.3` MIT.
     */
    val minRowCoverage: Float = 0.3f,
    /** Lignes de l'image inactives tolérées à l'intérieur d'une même marche. */
    val maxRowGap: Int = 2,
) {
    /** Ramène chaque seuil dans ses bornes (jamais d'exception sur le chemin du direct). */
    fun normalized(): StairsConfig = copy(
        cannyLow = cannyLow.coerceIn(1, 254),
        cannyHigh = maxOf(cannyLow.coerceIn(1, 254), cannyHigh.coerceIn(2, 255)),
        angleToleranceDeg = angleToleranceDeg.coerceIn(1, 44),
        minLines = minLines.coerceAtLeast(1),
        roiTopFraction = roiTopFraction.coerceIn(0f, 0.9f),
        minRowCoverage = minRowCoverage.coerceIn(0.05f, 1f),
        maxRowGap = maxRowGap.coerceIn(0, 20),
    )
}
