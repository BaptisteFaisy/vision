package dev.sam3vision.core.crossing

/**
 * Rang d'une boîte feu décodée : qui croire quand plusieurs lampes sont en
 * vue. Le prompt piéton capte aussi les tricolores (même tête visuelle,
 * `docs/MESURES-SAM3-FEUX.md` §10) et l'étiquette renvoyée est le wording du
 * prompt : un bout de tricolore passe [CrossingPrompts.isLightLabel] puis le
 * veto caisson (fragment, h/w < 3) et, au seul flux, éclipse le feu piéton —
 * un rouge voiture lu à la place d'un vert piéton dit d'attendre à tort
 * (ordre user du 04/11 : la décision ne se prend que sur le feu piéton).
 *
 * Ordre : vue entière avant bout coupé au bord du cadre, puis forme
 * piétonne (bonhomme troué) avant disque (plein), puis flux ([LampLens],
 * statut quo en cas d'égalité). Le rang n'écarte jamais : à un seul
 * candidat, ou à égalité, le verdict est inchangé.
 *
 * Pur Kotlin, testé en JVM (`PedLampRankTest`).
 */
object PedLampRank {

    /** Candidat noté : boîte normalisée 0..1, forme piétonne, flux lentille. */
    data class Ranked(val edgeTouch: Boolean, val ped: Float, val flux: Float)

    /**
     * Écart de forme en dessous duquel deux candidats sont ex æquo (le flux
     * tranche, comme avant) : 0,30 tombe dans le vide entre bonhommes
     * (≥ 0,84) et disques (≤ 0,25) mesurés (`PedLightGate`, `WIDE_FILL_MAX`).
     */
    const val PED_EPS = 0.30f

    /** Marge de contact au bord du cadre (bruit flottant de la normalisation). */
    private const val EDGE_EPS = 0.002f

    /**
     * Note du candidat [box] (normalisée 0..1) dont le crop [argb] ([width] ×
     * [height]) a pour flux [flux] : contact au bord ([isEdgeTouch]) et forme
     * piétonne ([pedScore]). Le verdict reste lu par ailleurs ([PedLightGate]).
     */
    fun rank(box: List<Float>, argb: IntArray, width: Int, height: Int, flux: Float): Ranked =
        Ranked(isEdgeTouch(box), pedScore(argb, width, height), flux)

    /** Vrai si [a] passe devant [b] : entier, puis bonhomme, puis flux. */
    fun better(a: Ranked, b: Ranked): Boolean {
        if (a.edgeTouch != b.edgeTouch) return b.edgeTouch
        if (kotlin.math.abs(a.ped - b.ped) > PED_EPS) return a.ped > b.ped
        return a.flux > b.flux
    }

    /**
     * Vrai si la boîte normalisée [box] touche un bord du cadre : un bout de
     * feu coupé, pas une vue entière. Boîte illisible ⇒ faux (l'aval rejette).
     */
    fun isEdgeTouch(box: List<Float>): Boolean {
        if (box.size < 4) return false
        val x1 = box[0]
        val y1 = box[1]
        val x2 = box[2]
        val y2 = box[3]
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return false
        if (x2 <= x1 || y2 <= y1) return false
        return x1 <= EDGE_EPS || y1 <= EDGE_EPS || x2 >= 1f - EDGE_EPS || y2 >= 1f - EDGE_EPS
    }

    /**
     * Forme piétonne de la tache allumée : `1 − remplissage` de la boîte de sa
     * **plus grande tache** 8-connexe (masque [isLampColour], daltonien —
     * bonhomme rouge et bonhomme vert sont troués, disques voiture pleins).
     * Sur la tache et pas sur tous les pixels : deux points rouges éloignés font
     * une boîte vide, donc 0,96 de forme, et battaient un vrai bonhomme. `0` sans
     * tache lisible (le flux tranche). Fraction, invariante à l'échelle.
     */
    fun pedScore(argb: IntArray, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0 || argb.size != width * height) return 0f
        val blob = largestBlob(BooleanArray(argb.size) { isLampColour(argb[it]) }, width, height)
        if (blob.size < 2) return 0f
        val minX = blob.minOf { it % width }
        val maxX = blob.maxOf { it % width }
        val minY = blob.minOf { it / width }
        val maxY = blob.maxOf { it / width }
        return 1f - blob.size.toFloat() / ((maxX - minX + 1) * (maxY - minY + 1))
    }

    /** Indices de la plus grande tache 8-connexe de [lit] (vidé au passage). */
    private fun largestBlob(lit: BooleanArray, width: Int, height: Int): IntArray {
        var best = IntArray(0)
        val queue = IntArray(lit.size)
        for (start in lit.indices) {
            if (!lit[start]) continue
            val size = flood(lit, start, width, height, queue)
            if (size > best.size) best = queue.copyOf(size)
        }
        return best
    }

    /** Remplit [queue] de la tache de [start], l'efface de [lit] et rend sa taille. */
    private fun flood(lit: BooleanArray, start: Int, width: Int, height: Int, queue: IntArray): Int {
        var head = 0
        var tail = 1
        queue[0] = start
        lit[start] = false
        while (head < tail) {
            val x = queue[head] % width
            val y = queue[head] / width
            head++
            for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                    val next = ny * width + nx
                    if (!lit[next]) continue
                    lit[next] = false
                    queue[tail++] = next
                }
            }
        }
        return tail
    }

    /**
     * Copie de [argb] réduite aux pixels de lampe ([isLampColour]), le reste
     * noir : ce que [LampLens.flux] doit noter pour comparer deux feux. Sans
     * elle, le flux compte le caisson gris-bleu d'un tricolore vu de côté.
     */
    fun lampOnly(argb: IntArray): IntArray =
        IntArray(argb.size) { index -> if (isLampColour(argb[index])) argb[index] else OFF }

    /**
     * Pixel allumé **et** franchement rouge ou vert : [PedLightColor.isLampPixel]
     * seul retient tout pixel saturé, dont le gris-bleu d'un caisson et le ciel —
     * 0,8 de forme pour un tricolore vu de côté contre 0,3-0,5 pour un vrai
     * bonhomme (vidéo Samsung `20261104_101521`), l'ordre « bonhomme avant
     * disque » s'y inversait. Mêmes seuils que [PedLightGate].
     */
    private fun isLampColour(pixel: Int): Boolean {
        if (!PedLightColor.isLampPixel(pixel)) return false
        val projection = PedLightColor.projectionOf(pixel)
        return projection <= PedLightColor.RED_PROJECTION_MAX || projection >= PedLightColor.GREEN_PROJECTION_MIN
    }

    /** Pixel éteint de [lampOnly] : noir opaque. */
    private const val OFF = 0xFF000000.toInt()
}
