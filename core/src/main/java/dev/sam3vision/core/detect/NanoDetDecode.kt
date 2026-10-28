package dev.sam3vision.core.detect

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Décodage NanoDet-Plus, miroir de `demo_android_ncnn/.../NanoDet.cpp`
 * (Apache-2.0, RangiLyu) : priors de grille, espérance DFL, NMS par classe.
 * Pur JVM : testé sans appareil.
 *
 * Les scores arrivent DÉJÀ sigmoïde : le graphe ONNX l'inclut (vérifié le
 * 2026-10-28 — max 0,84 sur image test, pas de logits). Ne pas re-sigmoïder.
 */
object NanoDetDecode {

    const val INPUT_SIZE = 320
    val STRIDES = intArrayOf(8, 16, 32, 64)
    const val REG_MAX = 7
    const val NUM_CLASS = 80

    /** Colonnes d'une ligne de sortie : 80 scores + 4 côtés × 8 paliers DFL. */
    const val ROW = NUM_CLASS + 4 * (REG_MAX + 1)

    /** Seuils de la démo amont (score 0,3, NMS 0,6-0,7). */
    const val SCORE_THRESHOLD = 0.35f
    const val NMS_THRESHOLD = 0.6f

    data class Prior(val x: Int, val y: Int, val stride: Int)

    /** 2125 priors (40² + 20² + 10² + 5²) : un par ligne de sortie. */
    fun priors(size: Int = INPUT_SIZE): List<Prior> {
        val out = ArrayList<Prior>(2125)
        for (stride in STRIDES) {
            val feat = (size + stride - 1) / stride
            for (y in 0 until feat) {
                for (x in 0 until feat) {
                    out.add(Prior(x, y, stride))
                }
            }
        }
        return out
    }

    /**
     * Sortie [n][112] → boîtes finales, en pixels de l'image d'origine
     * ([srcWidth]×[srcHeight]) : décodage par classe puis NMS par classe.
     * Forme inattendue ⇒ liste vide (jamais d'exception vers l'appelant).
     */
    fun detect(
        feats: Array<FloatArray>,
        srcWidth: Int,
        srcHeight: Int,
        scoreThreshold: Float = SCORE_THRESHOLD,
        nmsThreshold: Float = NMS_THRESHOLD,
    ): List<DetectBox> {
        val priors = priors()
        if (feats.size != priors.size) return emptyList()
        val perClass = Array(NUM_CLASS) { mutableListOf<DetectBox>() }
        for (i in feats.indices) {
            val row = feats[i]
            if (row.size < ROW) continue
            var best = 0
            var score = row[0]
            for (label in 1 until NUM_CLASS) {
                if (row[label] > score) {
                    score = row[label]
                    best = label
                }
            }
            if (score <= scoreThreshold) continue
            perClass[best].add(rowToBox(row, best, score, priors[i], srcWidth, srcHeight))
        }
        return perClass.flatMap { nms(it, nmsThreshold) }
    }

    /** Une ligne au-dessus du seuil → boîte (miroir de `disPred2Bbox`). */
    private fun rowToBox(
        row: FloatArray,
        label: Int,
        score: Float,
        p: Prior,
        srcWidth: Int,
        srcHeight: Int,
    ): DetectBox {
        val stride = p.stride.toFloat()
        val step = REG_MAX + 1
        val left = dflDistance(row, NUM_CLASS) * stride
        val top = dflDistance(row, NUM_CLASS + step) * stride
        val right = dflDistance(row, NUM_CLASS + 2 * step) * stride
        val bottom = dflDistance(row, NUM_CLASS + 3 * step) * stride
        val ctX = p.x * stride
        val ctY = p.y * stride
        val edge = INPUT_SIZE.toFloat()
        return DetectBox(
            x1 = max(ctX - left, 0f) * srcWidth / INPUT_SIZE,
            y1 = max(ctY - top, 0f) * srcHeight / INPUT_SIZE,
            x2 = min(ctX + right, edge) * srcWidth / INPUT_SIZE,
            y2 = min(ctY + bottom, edge) * srcHeight / INPUT_SIZE,
            label = label,
            labelName = COCO_LABELS.getOrElse(label) { "cls$label" },
            score = score,
        )
    }

    /** Espérance DFL d'un côté : softmax centrée sur le max, pas de 1. */
    private fun dflDistance(logits: FloatArray, from: Int): Float {
        var peak = logits[from]
        for (j in 1..REG_MAX) peak = max(peak, logits[from + j])
        var sum = 0f
        var acc = 0f
        for (j in 0..REG_MAX) {
            val e = exp(logits[from + j] - peak)
            sum += e
            acc += j * e
        }
        return if (sum > 0f) acc / sum else 0f
    }

    /** NMS d'une classe : tri décroissant, aires +1 px (miroir du C++). */
    fun nms(boxes: List<DetectBox>, iouThreshold: Float): List<DetectBox> {
        if (boxes.size < 2) return boxes
        val alive = boxes.sortedByDescending { it.score }.toMutableList()
        val areas = alive.map { (it.x2 - it.x1 + 1f) * (it.y2 - it.y1 + 1f) }.toMutableList()
        val kept = ArrayList<DetectBox>(alive.size)
        while (alive.isNotEmpty()) {
            val head = alive.removeAt(0)
            val headArea = areas.removeAt(0)
            kept.add(head)
            var j = 0
            while (j < alive.size) {
                if (iou(head, headArea, alive[j], areas[j]) >= iouThreshold) {
                    alive.removeAt(j)
                    areas.removeAt(j)
                } else {
                    j++
                }
            }
        }
        return kept
    }

    private fun iou(a: DetectBox, aArea: Float, b: DetectBox, bArea: Float): Float {
        val w = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1) + 1f)
        val h = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1) + 1f)
        val inter = w * h
        return inter / (aArea + bArea - inter)
    }
}
