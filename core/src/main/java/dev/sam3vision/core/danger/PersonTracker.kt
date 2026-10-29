package dev.sam3vision.core.danger

import dev.sam3vision.core.model.Detection

/**
 * Une personne suivie entre les frames : identité stable ([id]), boîte
 * normalisée 0..1, âge ([frames] vues) et trous ([missed] manquées d'affilée).
 * Le tracker ne voit que des boîtes — l'approche se lit dans leur grossissement.
 */
data class DangerTrack(
    val id: Int,
    val label: String,
    val score: Float,
    val box: List<Float>,
    val frames: Int,
    val missed: Int,
    val firstAtMs: Long,
    val lastAtMs: Long,
) {
    /** Largeur normalisée de la boîte. */
    val width: Float get() = box[2] - box[0]

    /** Hauteur normalisée de la boîte. */
    val height: Float get() = box[3] - box[1]

    /** Aire normalisée (0..1) : ce qui grossit quand on s'approche. */
    val area: Float get() = (width * height).coerceAtLeast(0f)

    /** Centre horizontal normalisé (0,5 = plein champ). */
    val centerX: Float get() = (box[0] + box[2]) / 2f

    /** Centre vertical normalisé. */
    val centerY: Float get() = (box[1] + box[3]) / 2f
}

/**
 * Recouvrement de deux boîtes normalisées `[x1,y1,x2,y2]` (0 = disjointes,
 * 1 = identiques). Pur : même entrées, même sortie.
 */
fun boxIou(a: List<Float>, b: List<Float>): Float {
    if (a.size < 4 || b.size < 4) return 0f
    val x1 = maxOf(a[0], b[0])
    val y1 = maxOf(a[1], b[1])
    val x2 = minOf(a[2], b[2])
    val y2 = minOf(a[3], b[3])
    val inter = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
    if (inter <= 0f) return 0f
    val union = boxArea(a) + boxArea(b) - inter
    if (union <= 0f) return 0f
    return (inter / union).coerceIn(0f, 1f)
}

/** Aire d'une boîte normalisée, 0 si dégénérée. */
fun boxArea(box: List<Float>): Float {
    if (box.size < 4) return 0f
    return ((box[2] - box[0]).coerceAtLeast(0f) * (box[3] - box[1]).coerceAtLeast(0f))
}

/**
 * Relie les boîtes « personne » entre les frames : la même personne qui bouge
 * garde son [DangerTrack.id] (glouton sur le score, appariement au meilleur
 * IoU ≥ [minIou]). Une piste manquée [maxMiss] frames d'affilée est oubliée —
 * la personne est sortie du champ ou le détecteur l'a perdue.
 *
 * Pur Kotlin, sans Android : les boîtes entrent en pixels ([Detection]), les
 * pistes sortent normalisées 0..1 (l'approche ne dépend pas de la résolution).
 */
class PersonTracker(
    private val minIou: Float = 0.3f,
    private val maxMiss: Int = 5,
) {
    private var nextId = 1
    private val tracks = mutableListOf<DangerTrack>()

    /**
     * Une frame de détections → les pistes à jour (appariées, créées,
     * vieillies, oubliées). Les détections sans taille sont ignorées.
     */
    fun update(detections: List<Detection>, nowMs: Long): List<DangerTrack> {
        val boxes = detections.mapNotNull { it.normalizedBox()?.let { box -> it to clamp(box) } }
        val matched = matchByScore(boxes, nowMs)
        ageUnmatched(matched, nowMs)
        tracks.removeAll { it.missed > maxMiss }
        return tracks.toList()
    }

    /** Pistes vivantes sans nouvelle frame (lecture seule). */
    fun alive(): List<DangerTrack> = tracks.toList()

    /** Oublie tout (nouvelle séance). */
    fun reset() {
        tracks.clear()
        nextId = 1
    }

    /**
     * Appariement glouton : chaque détection (meilleur score d'abord) prend la
     * piste libre au meilleur IoU, ou ouvre une piste si aucune ne convient.
     * Rend les pistes appariées (les autres vieillissent dans [ageUnmatched]).
     */
    private fun matchByScore(
        boxes: List<Pair<Detection, List<Float>>>,
        nowMs: Long,
    ): Set<Int> {
        val used = mutableSetOf<Int>()
        for ((detection, box) in boxes.sortedByDescending { it.first.score }) {
            val best = tracks
                .filter { it.id !in used }
                .maxByOrNull { boxIou(it.box, box) }
            if (best != null && boxIou(best.box, box) >= minIou) {
                used.add(best.id)
                replace(best.copy(box = box, score = detection.score, missed = 0, frames = best.frames + 1, lastAtMs = nowMs))
            } else {
                val fresh = DangerTrack(nextId++, detection.label, detection.score, box, 1, 0, nowMs, nowMs)
                used.add(fresh.id)
                tracks.add(fresh)
            }
        }
        return used
    }

    /** Les pistes non appariées prennent un trou (oubliées dans [update]). */
    private fun ageUnmatched(matched: Set<Int>, nowMs: Long) {
        for (track in tracks.filter { it.id !in matched }) {
            replace(track.copy(missed = track.missed + 1, lastAtMs = nowMs))
        }
    }

    private fun replace(next: DangerTrack) {
        val at = tracks.indexOfFirst { it.id == next.id }
        if (at >= 0) tracks[at] = next
    }

    private fun clamp(box: List<Float>): List<Float> =
        listOf(box[0].coerceIn(0f, 1f), box[1].coerceIn(0f, 1f), box[2].coerceIn(0f, 1f), box[3].coerceIn(0f, 1f))
}
