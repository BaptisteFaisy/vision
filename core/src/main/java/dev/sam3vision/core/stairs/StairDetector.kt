package dev.sam3vision.core.stairs

import dev.sam3vision.core.mask.GrayMask

/**
 * Verdict du détecteur sur une image : [detected] quand [lineCount] atteint
 * [StairsConfig.minLines], [confidence] en rampe (1/3 au seuil, 1,0 deux
 * lignes au-delà), [roiTop]/[roiBottom] la ROI analysée en lignes de pixels.
 */
data class StairSignal(
    val detected: Boolean,
    val confidence: Float,
    val lineCount: Int,
    val roiTop: Int,
    val roiBottom: Int,
) {
    companion object {
        /** Signal vide : image inexploitable, jamais d'exception sur le chemin du direct. */
        fun none(): StairSignal = StairSignal(detected = false, confidence = 0f, lineCount = 0, roiTop = 0, roiBottom = 0)
    }
}

/**
 * Détecteur d'escaliers classique, secours quand SAM ne segmente pas les
 * marches (constaté au téléphone le 28/10 : aucun concept escalier ne répond).
 * Adapté de `stair_detector.py` de `github.com/agrimbillowria/blind-project`
 * (licence MIT, voir `NOTICE` racine) : ROI bas 60 % → Canny(50, 150) →
 * lignes quasi-horizontales (±15°) → escalier si ≥ 4 lignes.
 *
 * Variante **projection horizontale** au lieu du Hough probabiliste : chaque
 * ligne de l'image compte ses pixels de contour quasi-horizontaux, une ligne
 * est active à 30 % de couverture (miroir du `minLineLength = w * 0,3` MIT),
 * les lignes actives voisines fusionnent en une marche, ≥ 4 marches =
 * escalier. Moins de code (~150 lignes Canny incluses contre ~300 avec Hough),
 * moins de faux positifs (un segment Hough isolé ne suffit plus, il faut une
 * vraie ligne de contour), suffisant pour un signal binaire « escalier devant ».
 *
 * Limites héritées du MIT (à documenter, pas à corriger ici) :
 * - ROI fixe (bas 60 %) : un escalier cadré en haut est invisible ;
 * - aucune distinction montée/descente ;
 * - faux positifs sur stores vénitiens, rayures et zébrures horizontales ;
 * - seuils calibrés webcam 640×480, à recalibrer sur captures du téléphone.
 *
 * Branché à la démo NanoDet on-device (`NanoDetScreen`, surcouche magenta +
 * `StairFrame`, 0 GPU) : NanoDet ne connaît aucune classe escalier (80 COCO),
 * ce détecteur la complète sur les mêmes pixels. Non branché au direct SAM ni
 * à la voix : [StairWatcher] (confirmation + cooldown) est l'API prête pour
 * ça. Bloqueurs produit avant la voix : phrase et cooldown à valider, et
 * surtout seuils à recalibrer sur captures réelles — une fausse annonce
 * « escalier » à un utilisateur aveugle est une décision de sécurité.
 *
 * Pur JVM, sans OpenCV (doctrine `:core`, comme `BlockMotionSource`).
 */
object StairDetector {

    /** Lignes au-delà du seuil pour une confiance de 1,0 (rampe 1/3, 2/3, 1). */
    private const val CONFIDENCE_RAMP = 3

    /**
     * Détecte un escalier dans [mask] (luminance 8 bits). Fonction totale :
     * image vide, incohérente ou ROI dégénérée = [StairSignal.none].
     */
    fun detect(mask: GrayMask, config: StairsConfig = StairsConfig()): StairSignal {
        val width = mask.width
        val height = mask.height
        if (width < 2 || height < 2 || mask.pixels.size < width * height) return StairSignal.none()
        val effective = config.normalized()
        val roiTop = (height * effective.roiTopFraction).toInt().coerceIn(0, height)
        if (height - roiTop < effective.minLines) return StairSignal.none()
        val edges = StairsCanny.horizontalEdges(mask, roiTop, height, effective)
        val lineCount = countLines(edges, width, roiTop, height, effective)
        val detected = lineCount >= effective.minLines
        return StairSignal(detected, confidenceFor(lineCount, effective.minLines), lineCount, roiTop, height)
    }

    /**
     * Confiance en rampe : 0 sous [minLines], 1/3 au seuil, 1,0 deux lignes
     * au-delà. Publique pour les tests ; la calibration sur captures réelles
     * pourra la remplacer par une courbe mesurée.
     */
    fun confidenceFor(lineCount: Int, minLines: Int): Float {
        if (lineCount < minLines) return 0f
        return ((lineCount - minLines + 1).toFloat() / CONFIDENCE_RAMP).coerceIn(0f, 1f)
    }

    /**
     * Lignes actives regroupées en marches : une ligne de l'image est active à
     * [StairsConfig.minRowCoverage] de couverture, les lignes actives séparées
     * par au plus [StairsConfig.maxRowGap] lignes inactives fusionnent.
     */
    internal fun countLines(edges: ByteArray, width: Int, top: Int, bottom: Int, config: StairsConfig): Int {
        val needed = (width * config.minRowCoverage).toInt().coerceAtLeast(1)
        var lines = 0
        var gap = config.maxRowGap + 1
        var open = false
        for (y in top until bottom) {
            val active = rowCoverage(edges, y * width, width) >= needed
            if (active) {
                if (!open) {
                    lines++
                    open = true
                }
                gap = 0
            } else if (open) {
                gap++
                if (gap > config.maxRowGap) open = false
            }
        }
        return lines
    }

    /** Pixels de contour quasi-horizontaux sur la ligne commençant à [offset]. */
    private fun rowCoverage(edges: ByteArray, offset: Int, width: Int): Int {
        var count = 0
        for (x in 0 until width) {
            if (edges[offset + x] != 0.toByte()) count++
        }
        return count
    }
}
