package dev.sam3vision.core.danger

import dev.sam3vision.core.model.Detection

/**
 * Décodage de la sortie YOLO11n `[84][8400]` (vérifié sur l'export maison) :
 * lignes 0..3 = `cx,cy,w,h` en pixels de l'entrée 640, lignes 4..83 = logits
 * des 80 classes COCO (sigmoïde à appliquer). Seule la classe 0 (personne) est
 * gardée — le pipeline urgence ne voit que les personnes.
 *
 * Pur Kotlin, sans Android ni ONNX : l'appelant lit le tenseur, ici on ne fait
 * que des maths (testables sur JVM avec un tenseur synthétique). Le resize
 * d'entrée écrase l'aspect (carré 640, comme le banc) : le retour aux pixels
 * source est une simple mise à l'échelle par axe.
 */
object YoloDecode {

    /** Classe COCO « person ». */
    const val PERSON_CLASS = 0

    /** Côté d'entrée du modèle (carré 640). */
    const val INPUT_SIZE = 640

    /** Étiquette des détections rendues (le français de l'app). */
    const val PERSON_LABEL = "personne"

    /**
     * Tenseur → personnes en pixels source. [rows] = les 84 lignes de 8400
     * colonnes ; [confThreshold] après sigmoïde ; [nmsIou] pour la NMS.
     */
    fun persons(
        rows: Array<FloatArray>,
        srcWidth: Int,
        srcHeight: Int,
        confThreshold: Float = 0.35f,
        nmsIou: Float = 0.5f,
    ): List<Detection> {
        require(rows.size > PERSON_CLASS + 4) { "tenseur YOLO inattendu : ${rows.size} lignes" }
        val cols = rows[0].size
        val scaleX = srcWidth / INPUT_SIZE.toFloat()
        val scaleY = srcHeight / INPUT_SIZE.toFloat()
        val candidates = mutableListOf<Detection>()
        for (c in 0 until cols) {
            val score = sigmoid(rows[4 + PERSON_CLASS][c])
            if (score < confThreshold) continue
            candidates.add(toDetection(rows, c, score, scaleX, scaleY, srcWidth, srcHeight))
        }
        return nms(candidates, nmsIou)
    }

    /** Une colonne → détection en pixels source, bornée à l'image. */
    private fun toDetection(
        rows: Array<FloatArray>,
        col: Int,
        score: Float,
        scaleX: Float,
        scaleY: Float,
        srcWidth: Int,
        srcHeight: Int,
    ): Detection {
        val cx = rows[0][col] * scaleX
        val cy = rows[1][col] * scaleY
        val w = rows[2][col] * scaleX
        val h = rows[3][col] * scaleY
        val x1 = (cx - w / 2f).coerceIn(0f, srcWidth.toFloat())
        val y1 = (cy - h / 2f).coerceIn(0f, srcHeight.toFloat())
        val x2 = (cx + w / 2f).coerceIn(0f, srcWidth.toFloat())
        val y2 = (cy + h / 2f).coerceIn(0f, srcHeight.toFloat())
        return Detection(PERSON_LABEL, score, listOf(x1, y1, x2, y2), srcWidth, srcHeight)
    }

    /**
     * Cap pré-NMS (pratique standard) : la NMS gloutonne est en n², et une
     * image hors distribution peut sortir des centaines de propositions —
     * sur le banc Samsung, 1764 candidats non plafonnés coûtaient ~2,5 s.
     * Sur une vraie frame (quelques personnes), le cap ne change rien.
     */
    const val MAX_CANDIDATES = 300

    /**
     * Suppression des non-maximums gloutonne : meilleur score d'abord, on jette
     * ce qui recouvre une boîte gardée (IoU ≥ [iou]) — les doublons YOLO.
     * Boîtes normalisées une seule fois (pas une allocation par paire).
     */
    private fun nms(candidates: List<Detection>, iou: Float): List<Detection> {
        val boxed = candidates.sortedByDescending { it.score }
            .take(MAX_CANDIDATES)
            .mapNotNull { detection -> detection.normalizedBox()?.let { detection to it } }
        val kept = mutableListOf<Pair<Detection, List<Float>>>()
        for ((detection, box) in boxed) {
            if (kept.none { boxIou(it.second, box) >= iou }) kept.add(detection to box)
        }
        return kept.map { it.first }
    }

    private fun sigmoid(x: Float): Float = (1f / (1f + kotlin.math.exp(-x)))
}
