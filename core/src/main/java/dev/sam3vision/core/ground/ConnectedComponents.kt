package dev.sam3vision.core.ground

/**
 * Étiquetage 8-connexe (comme `scipy.ndimage.label` par défaut) : [labels]
 * porte des ids de 1 à [count] (0 = fond), [areas] l'aire par id (indice 0 = 0).
 */
data class LabeledRegions(
    val labels: IntArray,
    val count: Int,
    val areas: IntArray,
)

/** Compte les bits vrais (seuils de graines et de régions). */
internal fun countTrue(mask: BooleanArray): Int {
    var count = 0
    for (i in mask.indices) {
        if (mask[i]) count++
    }
    return count
}

/** Composantes connexes union-find 2 passes + morphologie 3×3 (remplace scipy). */
object ConnectedComponents {

    /** Étiquette [mask] : union-find sur les 4 voisins déjà visités, puis renumérotation. */
    fun label(mask: BooleanArray, width: Int, height: Int): LabeledRegions {
        val labels = IntArray(width * height)
        if (mask.size != labels.size) return LabeledRegions(labels, 0, IntArray(1))
        val parent = IntArray(labels.size + 1) { it }
        var provisional = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask[y * width + x]) continue
                val mine = unionNeighbors(labels, parent, width, x, y)
                if (mine == 0) {
                    provisional++
                    parent[provisional] = provisional
                    labels[y * width + x] = provisional
                } else {
                    labels[y * width + x] = mine
                }
            }
        }
        return relabel(labels, parent, provisional)
    }

    /** Érosion 3×3 (`binary_erosion`, dehors = faux) : casse les ponts d'un pixel. */
    fun erode(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(width * height)
        if (mask.size != out.size) return out
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = neighborhoodAll(mask, width, height, x, y)
            }
        }
        return out
    }

    /** Dilatation 3×3 (`binary_dilation`, dehors = faux) : regonfle après érosion. */
    fun dilate(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(width * height)
        if (mask.size != out.size) return out
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = neighborhoodAny(mask, width, height, x, y)
            }
        }
        return out
    }

    /** Rattache le pixel à ses voisins étiquetés et fusionne leurs classes. */
    private fun unionNeighbors(
        labels: IntArray,
        parent: IntArray,
        width: Int,
        x: Int,
        y: Int,
    ): Int {
        var mine = 0
        val neighbors = intArrayOf(
            neighborLabel(labels, width, x - 1, y),
            neighborLabel(labels, width, x - 1, y - 1),
            neighborLabel(labels, width, x, y - 1),
            neighborLabel(labels, width, x + 1, y - 1),
        )
        for (k in neighbors.indices) {
            val other = neighbors[k]
            if (other == 0) continue
            if (mine == 0) {
                mine = other
            } else {
                union(parent, mine, other)
            }
        }
        return mine
    }

    private fun neighborLabel(labels: IntArray, width: Int, x: Int, y: Int): Int {
        if (x < 0 || y < 0) return 0
        if (x >= width) return 0
        return labels[y * width + x]
    }

    /** Renumérotation 1..N + aires, avec compression des chemins. */
    private fun relabel(labels: IntArray, parent: IntArray, provisional: Int): LabeledRegions {
        val mapping = IntArray(provisional + 1)
        var count = 0
        for (i in labels.indices) {
            if (labels[i] == 0) continue
            val root = findRoot(parent, labels[i])
            if (mapping[root] == 0) {
                count++
                mapping[root] = count
            }
            labels[i] = mapping[root]
        }
        val areas = IntArray(count + 1)
        for (i in labels.indices) {
            if (labels[i] != 0) areas[labels[i]]++
        }
        return LabeledRegions(labels, count, areas)
    }

    private fun union(parent: IntArray, a: Int, b: Int) {
        val rootA = findRoot(parent, a)
        val rootB = findRoot(parent, b)
        if (rootA != rootB) parent[rootB] = rootA
    }

    /** Racine avec compression de chemin, en boucles bornées (pas de `while`). */
    private fun findRoot(parent: IntArray, start: Int): Int {
        var root = start
        for (i in parent.indices) {
            val next = parent[root]
            if (next == root) break
            root = next
        }
        var x = start
        for (i in parent.indices) {
            if (x == root) break
            val next = parent[x]
            parent[x] = root
            x = next
        }
        return root
    }

    private fun neighborhoodAll(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) return false
                if (!mask[ny * width + nx]) return false
            }
        }
        return true
    }

    private fun neighborhoodAny(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                if (mask[ny * width + nx]) return true
            }
        }
        return false
    }
}
