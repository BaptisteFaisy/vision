package dev.sam3vision.core.depth

import dev.sam3vision.core.model.SegObject
import kotlin.math.abs

/**
 * Proximités serveur rattachées à **leur objet** d'une frame à l'autre.
 *
 * Le serveur ne calcule qu'une carte de profondeur sur 8 et répète ses médianes
 * entre deux **par rang** (`attach_cached_medians`), alors que les objets sont
 * triés par score et que cet ordre change à chaque frame : chaque objet recevait
 * la proximité d'un autre, puis ces paires fausses calibraient toute la frame.
 * Sur le Samsung le 04/11, une même personne passait de 5 m à 30 m d'une frame à
 * l'autre, et le côté d'esquive avec elle.
 *
 * Une frame répétée se reconnaît sans rien demander au serveur : ses valeurs sont
 * au rang près celles de la dernière vraie carte. Chaque objet y reprend alors la
 * proximité de l'objet de même nom le plus proche dans cette carte ; sans voisin,
 * il n'en a pas (le sténopé parle seul). Pur, testé en JVM.
 */
class DepthCarry(
    /** Frames répétées tolérées après une vraie carte, au-delà la proximité est périmée. */
    private val maxRepeats: Int = MAX_REPEATS,
) {

    private class Measured(val label: String, val box: List<Float>, val closeness: Float)

    private var ranks: List<Float?> = emptyList()
    private var measured: List<Measured> = emptyList()
    private var repeats = 0

    /** Proximités de la frame par boîte : celles du serveur, ou reprises de la dernière carte. */
    @Synchronized
    fun onFrame(objects: List<SegObject>, width: Int, height: Int): Map<List<Float>, Float> {
        val incoming = objects.map { obj -> obj.depth?.takeIf { it.isFinite() } }
        if (incoming.all { it == null }) return emptyMap()
        if (!repeatsLast(incoming)) return remember(objects, incoming)
        repeats++
        if (repeats > maxRepeats) return emptyMap()
        return carried(objects, width, height)
    }

    /** Nouvelle séance : la dernière carte ne vaut plus rien. */
    @Synchronized
    fun reset() {
        ranks = emptyList()
        measured = emptyList()
        repeats = 0
    }

    /** Vrai si chaque valeur reçue est, à son rang, celle de la dernière vraie carte. */
    private fun repeatsLast(incoming: List<Float?>): Boolean {
        if (ranks.isEmpty()) return false
        return incoming.indices.all { rank -> incoming[rank] == null || incoming[rank] == ranks.getOrNull(rank) }
    }

    private fun remember(objects: List<SegObject>, incoming: List<Float?>): Map<List<Float>, Float> {
        ranks = incoming
        repeats = 0
        measured = objects.indices.mapNotNull { index ->
            incoming[index]?.let { Measured(objects[index].label, objects[index].box, it) }
        }
        return measured.associate { it.box to it.closeness }
    }

    /** Appariement glouton, le plus proche d'abord : une mesure ne sert qu'à un objet. */
    private fun carried(objects: List<SegObject>, width: Int, height: Int): Map<List<Float>, Float> {
        if (width <= 0 || height <= 0) return emptyMap()
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for (index in objects.indices) {
            for (known in measured.indices) {
                val cost = costOf(objects[index], measured[known], width, height) ?: continue
                pairs += Triple(cost, index, known)
            }
        }
        pairs.sortBy { it.first }
        val out = HashMap<List<Float>, Float>()
        val usedObjects = HashSet<Int>()
        val usedMeasures = HashSet<Int>()
        for ((_, index, known) in pairs) {
            if (index in usedObjects || known in usedMeasures) continue
            usedObjects += index
            usedMeasures += known
            out[objects[index].box] = measured[known].closeness
        }
        return out
    }

    /** Écart de centre normalisé entre [obj] et [known], `null` hors de portée ou d'un autre nom. */
    private fun costOf(obj: SegObject, known: Measured, width: Int, height: Int): Float? {
        if (obj.label != known.label || obj.box.size < 4 || known.box.size < 4) return null
        val dx = abs(centerOf(obj.box, 0) - centerOf(known.box, 0)) / width
        val dy = abs(centerOf(obj.box, 1) - centerOf(known.box, 1)) / height
        return (dx + dy).takeIf { dx <= MAX_SHIFT && dy <= MAX_SHIFT }
    }

    private fun centerOf(box: List<Float>, axis: Int): Float = (box[axis] + box[axis + 2]) / 2f

    private companion object {
        /** Une carte sur 8 : le double, pour une frame de carte perdue. */
        const val MAX_REPEATS = 16

        /** Déplacement de centre toléré entre deux frames (fraction d'image). */
        const val MAX_SHIFT = 0.12f
    }
}
