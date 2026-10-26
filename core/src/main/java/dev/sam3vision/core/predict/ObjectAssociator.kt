package dev.sam3vision.core.predict

/**
 * Association « objets serveur sans id » → suivis locaux, par recouvrement (IoU).
 *
 * Le tracker `POST /frame` renvoie des **ids stables** : l'association est alors
 * gratuite. Le point-à-frame `POST /live` n'en renvoie pas : sans association locale,
 * le suivi local est impossible (aucune notion de « le même objet » d'une frame à
 * l'autre). C'est ce classeur qui comble ce trou.
 *
 * Méthode : glouton par IoU décroissant, un objet serveur ne peut prendre qu'un id,
 * un id ne peut prendre qu'un objet. Au-delà de [maxIou], l'objet serveur est
 * « nouveau » (id libre) et l'ancien suivi finit par expirer.
 */
class ObjectAssociator(private val maxIou: Float = DEFAULT_MAX_IOU) {

    /**
     * Associe [server] aux suivis connus ([tracks] : id → dernière observation).
     * Renvoie id → objet serveur ; un objet sans association n'apparaît pas dans la
     * carte (l'appelant lui crée un id neuf).
     */
    fun assign(server: List<ServerObject>, tracks: Map<Int, ServerObject>): Map<Int, ServerObject> {
        val pairs = buildPairs(server, tracks)
        val takenServer = HashSet<Int>()
        val takenTrack = HashSet<Int>()
        val out = HashMap<Int, ServerObject>()
        for ((serverIndex, trackId, iou) in pairs) {
            if (serverIndex in takenServer || trackId in takenTrack) continue
            takenServer += serverIndex
            takenTrack += trackId
            out[trackId] = server[serverIndex]
        }
        return out
    }

    /** Id libre pour un objet serveur non associé : le plus petit disponible. */
    fun freeId(used: Set<Int>): Int {
        var candidate = 1
        while (candidate in used) candidate++
        return candidate
    }

    private fun buildPairs(server: List<ServerObject>, tracks: Map<Int, ServerObject>): List<Triple<Int, Int, Float>> {
        val out = ArrayList<Triple<Int, Int, Float>>()
        server.forEachIndexed { index, obj ->
            tracks.forEach { (id, last) ->
                val iou = obj.box.iou(last.box)
                if (iou >= maxIou) out += Triple(index, id, iou)
            }
        }
        return out.sortedByDescending { it.third }
    }

    companion object {
        /** IoU minimal pour considérer que c'est le même objet. */
        const val DEFAULT_MAX_IOU = 0.3f
    }
}
