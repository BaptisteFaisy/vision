package dev.sam3vision.core.danger

/** Appariement optimal filtré : paires (ligne, colonne), lignes et colonnes seules. */
data class Assignment(
    val matches: List<Pair<Int, Int>>,
    val unmatchedRows: List<Int>,
    val unmatchedCols: List<Int>,
)

/**
 * Affectation linéaire rectangulaire (Kuhn-Munkres à potentiels, O(n³)) :
 * appariement bijectif de coût minimal, puis filtre au seuil [thresh]
 * (comme la branche sans-`lap` d'ultralytics : paires > seuil rendues aux
 * non-appariés). Les égalités parfaites de coût peuvent apparier
 * différemment d'un solveur à l'autre — le coût total reste optimal.
 *
 * Écrit d'après l'algorithme des manuels (pas du code ultralytics) : pur
 * Kotlin, zéro dépendance.
 */
object Hungarian {

    /** Coût minimal puis filtre : paires ≤ [thresh] gardées, le reste est seul. */
    fun assign(cost: Array<DoubleArray>, thresh: Double): Assignment {
        val rows = cost.size
        val cols = if (rows == 0) 0 else cost[0].size
        if (rows == 0 || cols == 0) {
            return Assignment(emptyList(), (0 until rows).toList(), (0 until cols).toList())
        }
        val pairs = if (rows <= cols) solve(cost) else solve(transpose(cost)).map { (r, c) -> c to r }
        val kept = pairs.filter { (r, c) -> cost[r][c] <= thresh }
        val usedRows = kept.map { it.first }.toSet()
        val usedCols = kept.map { it.second }.toSet()
        return Assignment(
            kept.sortedBy { it.first },
            (0 until rows).filter { it !in usedRows },
            (0 until cols).filter { it !in usedCols },
        )
    }

    /**
     * Kuhn-Munkres, lignes ≤ colonnes : potentiels `u`/`v`, couplage `p`/`way`
     * (indices +1, 0 = libre). Chaque ligne s'augmente par le plus court chemin.
     */
    private fun solve(cost: Array<DoubleArray>): List<Pair<Int, Int>> {
        val n = cost.size
        val m = cost[0].size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val dist = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..m) {
                    if (used[j]) continue
                    val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < dist[j]) {
                        dist[j] = cur
                        way[j] = j0
                    }
                    if (dist[j] < delta) {
                        delta = dist[j]
                        j1 = j
                    }
                }
                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        dist[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        return (1..m).filter { p[it] != 0 }.map { j -> (p[j] - 1) to (j - 1) }
    }

    private fun transpose(cost: Array<DoubleArray>): Array<DoubleArray> =
        Array(cost[0].size) { c -> DoubleArray(cost.size) { r -> cost[r][c] } }
}
