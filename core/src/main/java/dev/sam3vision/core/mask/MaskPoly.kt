package dev.sam3vision.core.mask

import kotlin.math.ceil

/**
 * Décodage du format `poly` (`mask_format: "poly"`) : des **anneaux entiers** exprimés dans
 * la grille du masque, remplis en **even-odd** — un trou, c'est un anneau de plus, et
 * l'intersection d'un nombre impair d'arêtes est de l'objet. C'est la règle du serveur, donc
 * le même masque se relit ici qu'il ait été envoyé en RLE ou en polygones.
 *
 * Chaque anneau est **plat** (`x0, y0, x1, y1, …`) et refermé implicitement ; un anneau de
 * moins de trois sommets est ignoré, et un remplissage vide rend `null`.
 */
fun decodePolyMask(rings: List<List<Int>>, width: Int, height: Int): GrayMask? {
    val total = maskGridSize(width, height) ?: return null
    val edges = polyEdges(rings)
    if (edges.isEmpty()) return null
    val pixels = ByteArray(total)
    val crossings = ArrayList<Float>(16)
    for (y in 0 until height) {
        crossings.clear()
        collectCrossings(edges, y + 0.5f, crossings)
        fillScanline(pixels, y * width, width, crossings)
    }
    return GrayMask(width, height, pixels)
}

/** Arêtes de tous les anneaux (`x1, y1, x2, y2` par arête), anneaux dégénérés ôtés. */
private fun polyEdges(rings: List<List<Int>>): FloatArray {
    val edges = ArrayList<Float>(64)
    for (ring in rings) {
        val points = ring.size / 2
        if (points < 3) continue
        for (i in 0 until points) appendEdge(edges, ring, i, (i + 1) % points)
    }
    return edges.toFloatArray()
}

/** Une arête d'un anneau, sautée si elle est horizontale ou nulle : elle ne croise rien. */
private fun appendEdge(edges: MutableList<Float>, ring: List<Int>, from: Int, to: Int) {
    val x1 = ring[2 * from].toFloat()
    val y1 = ring[2 * from + 1].toFloat()
    val x2 = ring[2 * to].toFloat()
    val y2 = ring[2 * to + 1].toFloat()
    if (y1 == y2) return
    edges.add(x1)
    edges.add(y1)
    edges.add(x2)
    edges.add(y2)
}

/**
 * Abscisses où la ligne [scanY] coupe les arêtes, en règle **demi-ouverte** `[y1, y2)` :
 * un sommet partagé par deux arêtes n'est compté qu'une fois, sinon les anneaux se
 * rempliraient par bandes.
 */
private fun collectCrossings(edges: FloatArray, scanY: Float, out: MutableList<Float>) {
    var i = 0
    while (i + 3 < edges.size) {
        val y1 = edges[i + 1]
        val y2 = edges[i + 3]
        if (scanY >= minOf(y1, y2) && scanY < maxOf(y1, y2)) {
            out.add(edges[i] + (scanY - y1) * (edges[i + 2] - edges[i]) / (y2 - y1))
        }
        i += 4
    }
}

/** Remplit les intervalles pairs de croisements, en centres de pixels (`x + 0,5`). */
private fun fillScanline(pixels: ByteArray, row: Int, width: Int, crossings: MutableList<Float>) {
    crossings.sort()
    var i = 0
    while (i + 1 < crossings.size) {
        val from = maxOf(ceil(crossings[i] - 0.5f).toInt(), 0)
        val to = minOf(ceil(crossings[i + 1] - 0.5f).toInt(), width)
        for (x in from until to) pixels[row + x] = 255.toByte()
        i += 2
    }
}