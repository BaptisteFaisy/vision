package dev.sam3vision.core.stairs

/**
 * Seuils du détecteur d'escaliers classique, adapté de `config.py` (§5) de
 * `github.com/agrimbillowria/blind-project` (licence MIT, voir `NOTICE` racine).
 *
 * Recalibrés pour la luminance téléphone 256×103 (les valeurs MIT visaient
 * une webcam 640×480 : à cette échelle les gradients downsamplés n'atteignent
 * plus 150 et le flou 5×5 écrase les nez de marche — le compteur restait à
 * 0 sur de vrais escaliers). Ratio 1:3 bas/haut conservé (pratique Canny) ;
 * validation live sur captures réelles recommandée avant tout resserrage.
 */
data class StairsConfig(
    /** Seuil bas de Canny (MIT : 50 sur webcam 640×480). */
    val cannyLow: Int = 30,
    /** Seuil haut de Canny (MIT : 150 sur webcam 640×480). */
    val cannyHigh: Int = 90,
    /** Écart à l'horizontale encore compté (MIT : 15°, degrés) : 25° suit la perspective du téléphone tenu en main. */
    val angleToleranceDeg: Int = 25,
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
