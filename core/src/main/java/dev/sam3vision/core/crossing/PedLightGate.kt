package dev.sam3vision.core.crossing

/**
 * Porte anti-confusion du vert piéton : un vert classifié ne devient un ordre
 * de traverser que s'il ressemble à un bonhomme — élancé, pas confiné en
 * haut, sans rouge franc mélangé. Un feu de voiture (disque rond), un reflet
 * ou un bruit retombe en [CrossingState.Unknown] : la porte ne fabrique
 * jamais de vert, elle n'en refuse que — et un refus est un doute, pas un
 * rouge forcé (l'aval attend dans les deux cas).
 *
 * Porte **calibrée lampe serrée** : seuils mesurés sur feux français
 * (10/2026, corpus Panoramax 235 lampes labellisées, crops ≤ 48 px comme en
 * production alors) : 0 vert sur 90 rouges, 28/42 bonhommes verts acceptés,
 * 0/47 disques voiture de face acceptés (2 disques de profil passent : vue
 * impossible téléphone en main face au passage, et la confirmation
 * temporelle les filtre). Hypothèse cadrage : la boîte SAM serre la lampe
 * (lampe centrée — `CrossingPixels` recentre sur le centroïde saturé quand
 * elle s'écarte ; le « bas du cadre » d'avant ne sépare plus rien) —
 * [GREEN_BOTTOM_MIN] n'est plus un prior de position mais une garde
 * anti-bruit-haut. Les fractions sont invariantes d'échelle (crop 96 px
 * depuis le 31/10) ; [BBOX_MIN_PX] reste à 3 px (un point ne fait pas un
 * bonhomme, même en 96 px). Poses larges et pictogrammes non standard :
 * doute documenté (voir test `pose large`).
 * Pur Kotlin, testé en JVM (synthétique + échantillons `feuxfr/`, `feuxfr2/`).
 *
 * R1b (29/10/2026, lot FR jour 6 573 détections SAM triées) : dans la
 * bande 20-35 % de vert en bas (quasi-raté de la garde), la forme du nuage
 * tranche — allongé, avec de l'intérieur, masse non entassée en haut.
 * Mesuré : rappel vert 81/183 → 90/183, fuites inchangées (0 rouge,
 * 100 voiture, 6 éteints), seuils existants intacts.
 *
 * Seuils publics (surface de calibrage, voir aussi [PedLightColor]).
 */
object PedLightGate {

    /** Le vert ne doit pas être confiné en haut (bruit sur un rouge). */
    const val GREEN_BOTTOM_MIN = 0.35f

    /** Rouge franc toléré avec un vert (au-delà : signal mélangé, doute). */
    const val RED_MAX_FRACTION = 0.02f

    /**
     * Vert minimal dans le cadre (en dessous : reflet ou bruit). Partagé
     * avec [TrainedLightColor] : mêmes pixels, même plancher, pas de vert
     * sans vert.
     */
    const val GREEN_MIN_FRACTION = 0.004f

    /** Élancement minimal de la tache verte (un disque est aussi large que haut). */
    const val ELONGATION_MIN = 1.5f

    /** Hauteur minimale de la tache verte, en pixels (pas un point). */
    const val BBOX_MIN_PX = 3

    /** R1b : masse verte max du tiers haut de bbox (bonhomme non entassé en haut). */
    const val TOP3_MAX = 0.38f

    /** R1b : fraction max de pixels verts en lisière (un disque n'est que contour). */
    const val EDGE_MAX = 0.72f

    /** R1b : allongement vertical min du nuage (écart-types, pas bbox). */
    const val CLOUD_ELONGATION_MIN = 1.5f

    /**
     * R1b : plancher bas — à 20 % ou moins, rejet dur sans appel (contrat
     * historique : la garde bas garde ses dents, la forme ne tranche que
     * dans la bande 20-35 %, les quasi-ratés).
     */
    const val R1B_BOTTOM_FLOOR = 0.20f

    /**
     * Verdict sûr du crop [argb] ([width] x [height]) : le vert de l'arbre ne
     * passe que la porte, le rouge franc de l'arbre reste rouge, tout le
     * reste doute ([CrossingState.Unknown]) — un refus de porte ou une
     * feuille fragile attendent comme un rouge, sans s'y forcer.
     */
    fun verdict(argb: IntArray, width: Int, height: Int): CrossingState {
        val raw = TrainedLightColor.classify(argb, width, height)
        if (raw == CrossingState.Unknown) return CrossingState.Unknown
        if (raw != CrossingState.Green) return CrossingState.Red
        if (acceptGreen(argb, width, height)) return CrossingState.Green
        return CrossingState.Unknown
    }

    /** Vrai si le vert du crop a la position et la forme d'un bonhomme. */
    fun acceptGreen(argb: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || argb.size != width * height) return false
        val scan = scan(argb, width, height)
        val total = argb.size.toFloat()
        if (scan.green / total < GREEN_MIN_FRACTION) return false
        if (scan.red / total >= RED_MAX_FRACTION) return false
        val boxWidth = scan.maxX - scan.minX + 1
        val boxHeight = scan.maxY - scan.minY + 1
        if (boxHeight < BBOX_MIN_PX) return false
        if (boxHeight.toFloat() < boxWidth * ELONGATION_MIN) return false
        val bottomFraction = scan.greenBottom.toFloat() / scan.green
        if (bottomFraction >= GREEN_BOTTOM_MIN) return true
        if (bottomFraction <= R1B_BOTTOM_FLOOR) return false
        // R1b : quasi-raté de la garde bas — la forme du nuage tranche.
        return acceptShaped(argb, width, height)
    }

    /** R1b : nuage vert allongé, avec de l'intérieur, masse non entassée en haut. */
    private fun acceptShaped(argb: IntArray, width: Int, height: Int): Boolean {
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        for (index in argb.indices) {
            val pixel = argb[index]
            if (!isLit(pixel)) continue
            if (!isGreenHue(PedLightColor.hueDeg(pixel))) continue
            xs.add(index % width)
            ys.add(index / width)
        }
        if (xs.isEmpty()) return false
        if (cloudElongation(xs, ys) < CLOUD_ELONGATION_MIN) return false
        if (topThird(ys) > TOP3_MAX) return false
        return edgeFraction(xs, ys, width, height) <= EDGE_MAX
    }

    /** Allongement vertical du nuage (écart-type y / écart-type x). */
    private fun cloudElongation(xs: List<Int>, ys: List<Int>): Float {
        val n = xs.size.toDouble()
        val mx = xs.sumOf { it.toDouble() } / n
        val my = ys.sumOf { it.toDouble() } / n
        val xstd = Math.sqrt(xs.sumOf { (it - mx) * (it - mx) } / n)
        val ystd = Math.sqrt(ys.sumOf { (it - my) * (it - my) } / n)
        return (ystd / (xstd + 1e-9)).toFloat()
    }

    /** Part du vert dans le tiers haut des lignes de bbox. */
    private fun topThird(ys: List<Int>): Float {
        val lo = ys.min()
        val hi = ys.max()
        val prof = IntArray(hi - lo + 1)
        for (y in ys) prof[y - lo]++
        val third = maxOf(1, prof.size / 3)
        return prof.take(third).sum().toFloat() / ys.size
    }

    /** Part des pixels verts touchant du non-vert (voisinage 4). */
    private fun edgeFraction(xs: List<Int>, ys: List<Int>, width: Int, height: Int): Float {
        val grid = BooleanArray(width * height)
        for (k in xs.indices) grid[ys[k] * width + xs[k]] = true
        val lo = ys.min()
        val hi = ys.max()
        var edge = 0
        var n = 0
        for (y in lo..hi) {
            for (x in 0 until width) {
                if (!grid[y * width + x]) continue
                n++
                if (isEdge(grid, width, height, x, y)) edge++
            }
        }
        return edge.toFloat() / n
    }

    /** Vrai si le pixel vert a au moins un voisin 4 non vert. */
    private fun isEdge(grid: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        val neighbors = intArrayOf(x + 1, y, x - 1, y, x, y + 1, x, y - 1)
        for (k in neighbors.indices step 2) {
            val nx = neighbors[k]
            val ny = neighbors[k + 1]
            if (nx !in 0 until width || ny !in 0 until height || !grid[ny * width + nx]) return true
        }
        return false
    }

    /** Balayage du crop : compteurs de couleur et boîte englobante du vert. */
    private fun scan(argb: IntArray, width: Int, height: Int): Scan {
        val scan = Scan()
        val half = height / 2
        for (index in argb.indices) {
            val pixel = argb[index]
            if (!isLit(pixel)) continue
            val hue = PedLightColor.hueDeg(pixel)
            if (isRedHue(hue)) {
                scan.red += 1
            } else if (isGreenHue(hue)) {
                scan.green += 1
                if (index / width >= half) scan.greenBottom += 1
                scan.minX = minOf(scan.minX, index % width)
                scan.maxX = maxOf(scan.maxX, index % width)
                scan.minY = minOf(scan.minY, index / width)
                scan.maxY = maxOf(scan.maxY, index / width)
            }
        }
        return scan
    }

    /** Pixel allumé franc (mêmes planchers que [PedLightColor]). */
    private fun isLit(pixel: Int): Boolean {
        if (PedLightColor.saturation(pixel) < PedLightColor.MIN_SATURATION) return false
        if (PedLightColor.value(pixel) < PedLightColor.MIN_VALUE) return false
        return true
    }

    private fun isRedHue(hue: Float): Boolean {
        if (hue <= PedLightColor.RED_HUE_DEG) return true
        return hue >= 360f - PedLightColor.RED_HUE_DEG
    }

    private fun isGreenHue(hue: Float): Boolean {
        if (hue < PedLightColor.GREEN_HUE_MIN_DEG) return false
        return hue <= PedLightColor.GREEN_HUE_MAX_DEG
    }

    /** Compteurs du balayage, boîte du vert en coordonnées pixels. */
    private class Scan(
        var green: Int = 0,
        var greenBottom: Int = 0,
        var red: Int = 0,
        var minX: Int = Int.MAX_VALUE,
        var maxX: Int = Int.MIN_VALUE,
        var minY: Int = Int.MAX_VALUE,
        var maxY: Int = Int.MIN_VALUE,
    )
}
