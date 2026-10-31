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
 * Région d'intérêt normalisée 0..1 (`left, top, right, bottom`) : la zone où
 * SAM reconnaît l'escalier (sa boîte normalisée), où le Canny compte les
 * marches — sans elle, le bas fixe de l'image ([StairsConfig.roiTopFraction]).
 */
data class StairRoi(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /**
     * ROI triée et bornée à 0..1 ; `null` si dégénérée (largeur ou hauteur
     * nulle : rien à compter, jamais d'exception).
     */
    fun normalized(): StairRoi? {
        val l = minOf(left, right).coerceIn(0f, 1f)
        val r = maxOf(left, right).coerceIn(0f, 1f)
        val t = minOf(top, bottom).coerceIn(0f, 1f)
        val b = maxOf(top, bottom).coerceIn(0f, 1f)
        if (r <= l || b <= t) return null
        return StairRoi(l, t, r, b)
    }
}

/**
 * Détecteur d'escaliers classique, secours quand SAM ne segmente pas les
 * marches (constaté au téléphone le 28/10 : aucun concept escalier ne répond).
 * Adapté de `stair_detector.py` de `github.com/agrimbillowria/blind-project`
 * (licence MIT, voir `NOTICE` racine) : ROI bas 60 % → Canny(30, 90) →
 * lignes quasi-horizontales (±25°) → escalier si ≥ 4 lignes.
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
 * - sans [StairRoi] (boîte SAM), ROI fixe (bas 60 %) : un escalier cadré en
 *   haut est invisible ; avec ROI, c'est la reconnaissance SAM qui cadre ;
 * - aucune distinction montée/descente ;
 * - faux positifs sur stores vénitiens, rayures et zébrures horizontales.
 * Seuils et flou recalibrés pour la luminance téléphone 256×103 (voir
 * `StairsConfig`) : les valeurs MIT 640×480 laissaient le compteur à 0.
 *
 * Branché à la voix du direct : l'analyseur caméra offre le plan de luminance
 * (`StairCannyCount`, ni overlay ni affichage des marches) et le guetteur
 * escaliers le relit quand SAM nomme l'escalier — le compte à rebours
 * (« Plus que N marches. ») parle alors, au lieu de l'annonce sèche.
 * Garde-fou inchangé : l'annonce reste conditionnée aux libellés SAM (le
 * Canny seul ne parle jamais) — une fausse annonce « escalier » à un
 * utilisateur aveugle est une décision de sécurité, pas un défaut.
 * (NanoDet, un temps envisagé comme hôte on-device, est en cours de
 * suppression de l'app : ne pas câbler vers `app/detect`.)
 *
 * Pur JVM, sans OpenCV (doctrine `:core`, comme `BlockMotionSource`).
 */
object StairDetector {

    /** Lignes au-delà du seuil pour une confiance de 1,0 (rampe 1/3, 2/3, 1). */
    private const val CONFIDENCE_RAMP = 3

    /**
     * Détecte un escalier dans [mask] (luminance 8 bits), dans [roi] quand elle
     * est posée (la boîte SAM de l'escalier : le comptage est calibré sur sa
     * reconnaissance), sinon dans le bas fixe de l'image. Fonction totale :
     * image vide, incohérente ou ROI dégénérée = [StairSignal.none].
     */
    fun detect(
        mask: GrayMask,
        config: StairsConfig = StairsConfig(),
        roi: StairRoi? = null,
    ): StairSignal {
        val width = mask.width
        val height = mask.height
        if (width < 2 || height < 2 || mask.pixels.size < width * height) return StairSignal.none()
        val effective = config.normalized()
        val bounds = roi?.normalized()?.toPixels(width, height)
        if (roi != null && bounds == null) return StairSignal.none()
        val roiTop = bounds?.top ?: (height * effective.roiTopFraction).toInt().coerceIn(0, height)
        val roiBottom = bounds?.bottom ?: height
        if (roiBottom - roiTop < effective.minLines) return StairSignal.none()
        val edges = StairsCanny.horizontalEdges(mask, roiTop, roiBottom, effective)
        val lineCount = if (bounds == null) {
            countLines(edges, width, roiTop, roiBottom, effective)
        } else {
            countLinesIn(edges, width, roiTop, roiBottom, bounds.left, bounds.right, effective)
        }
        val detected = lineCount >= effective.minLines
        return StairSignal(detected, confidenceFor(lineCount, effective.minLines), lineCount, roiTop, roiBottom)
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

    /**
     * Marches dans le rectangle `[left, right) × [top, bottom)` : comme
     * [countLines], mais la couverture minimale est proportionnée à la largeur
     * observée (miroir de `minLineLength` : un escalier cadré par SAM n'a pas
     * la largeur de l'image).
     */
    internal fun countLinesIn(
        edges: ByteArray,
        width: Int,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        config: StairsConfig,
    ): Int {
        val needed = ((right - left) * config.minRowCoverage).toInt().coerceAtLeast(1)
        var lines = 0
        var gap = config.maxRowGap + 1
        var open = false
        for (y in top until bottom) {
            val active = rowCoverageIn(edges, y * width + left, right - left) >= needed
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

    /** Pixels de contour quasi-horizontaux sur [span] colonnes depuis [offset]. */
    private fun rowCoverageIn(edges: ByteArray, offset: Int, span: Int): Int {
        var count = 0
        for (x in 0 until span) {
            if (edges[offset + x] != 0.toByte()) count++
        }
        return count
    }

    /** Bornes pixels d'une ROI normalisée (`left`/`top` inclus, `right`/`bottom` exclus). */
    private data class RoiPixels(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** ROI normalisée → bornes pixels, `null` si rien ne reste à l'échelle. */
    private fun StairRoi.toPixels(width: Int, height: Int): RoiPixels? {
        val left = (left * width).toInt().coerceIn(0, width)
        val right = (right * width).toInt().coerceIn(0, width)
        val top = (top * height).toInt().coerceIn(0, height)
        val bottom = (bottom * height).toInt().coerceIn(0, height)
        if (right <= left || bottom <= top) return null
        return RoiPixels(left, top, right, bottom)
    }
}
