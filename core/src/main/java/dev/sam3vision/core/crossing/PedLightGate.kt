package dev.sam3vision.core.crossing

/**
 * Porte du vert piéton : un vert classifié ne devient un ordre de traverser
 * que s'il n'est pas confiné en haut et sans rouge franc mélangé. Détection
 * au plus large (31/10, ordre user) : la forme n'exige qu'une tache au moins
 * aussi haute que large — élancés comme ronds passent à 1,0 ; les disques
 * au contour cyan complet (tache plus large que haute) restent refusés,
 * réalité par ancre (voir `PedLightGateTest` et `docs/RECETTE-FEUX-FR.md`).
 * Les prompts piétons restent la garde contre les disques admis, avec le
 * veto caisson en amont ([CarHousingVeto] : tricolores francs écartés à la
 * sélection, 01/11) — un veto par crop a été mesuré puis rejeté
 * (recouvrement disque/bonhomme, voir `docs/MESURES-SAM3-FEUX.md` §11).
 *
 * Historique (10/2026, corpus Panoramax 235 lampes, crops ≤ 48 px) : à
 * élancement 1,5, 0 vert sur 90 rouges, 28/42 bonhommes, 0/47 disques de
 * face — chiffres caducs à 1,0, non remesurés sur les 235 (échantillon
 * vendu `feuxfr/`, `feuxfr2/` : 11/11 verts, 4 disques admis).
 * Pur Kotlin, testé en JVM (synthétique + échantillons `feuxfr/`, `feuxfr2/`).
 *
 * R1b (29/10/2026, lot FR jour 6 573 détections SAM triées) : dans la
 * bande 20-35 % de vert en bas (quasi-raté de la garde), la forme du nuage
 * tranche — allongé, avec de l'intérieur, masse non entassée en haut.
 * Mesuré : rappel vert 81/183 → 90/183, fuites inchangées (0 rouge,
 * 100 voiture, 6 éteints), seuils existants intacts.
 * 01/11 : bande élargie 20-35 % → 15-35 % (même ordre, jitter ±1 px).
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
     * sans vert. 0,002 depuis le 31/10 (ordre user : feux petits et
     * lointains) ; les disques voiture (≥ 0,046) restent hors d'atteinte
     * de ce plancher et la porte forme tranche ensuite.
     */
    internal const val GREEN_MIN_FRACTION = 0.002f

    /**
     * Élancement minimal de la tache verte : au moins aussi haute que large.
     * 1,0 depuis le 31/10 (ordre user : détection au plus large — à 1,5, les
     * verts cadrés caisson SAM ≤ 1,11 étaient refusés avant les disques
     * voiture 1,30/1,47 ; les disques passent désormais aussi).
     */
    private const val ELONGATION_MIN = 1.0f

    /**
     * Remplissage maximal de la boîte pour une tache **plus large que haute**.
     *
     * Une tache large est la signature d'un disque vu de face — mais un disque
     * allumé est **plein** (0,75 à 0,88 de sa boîte mesurés), alors qu'un
     * bonhomme, même cadré de près ou nimbé d'un halo, laisse des trous
     * (0,09 et 0,16 sur les deux verts réels concernés). 0,35 tombe dans le
     * vide entre les deux populations (facteur ≈ 2 de chaque côté), donc ce
     * n'est pas un seuil ajusté à un cas : la mesure ne bouge pas entre 0,30
     * et 0,40.
     *
     * Mesuré le 01/11/2026 sur les 49 crops téléphone FR : rappel vert
     * 18/20 → **20/20**, aucun faux vert, 31/31 ancres ; sur 400 crops PTL
     * (autre pays, contrôle indépendant) les faux verts sont inchangés (13).
     */
    private const val WIDE_FILL_MAX = 0.35f

    /** Hauteur minimale de la tache verte, en pixels (pas un point). */
    const val BBOX_MIN_PX = 3

    /** R1b : masse verte max du tiers haut de bbox (bonhomme non entassé en haut). */
    const val TOP3_MAX = 0.38f

    /**
     * R1b : fraction max de pixels verts en lisière (un disque n'est que
     * contour). 0,75 depuis le 01/11 (ordre user : vert plus facile —
     * `st_g02` décalé de 1 px vers le haut sort à 0,73 ; bascules jitter
     * ±2 px sur 31 ancres : 11 → 5, 0 rouge bougé).
     */
    const val EDGE_MAX = 0.75f

    /** R1b : allongement vertical min du nuage (1,0 comme la bbox, même ordre). */
    private const val CLOUD_ELONGATION_MIN = 1.0f

    /**
     * R1b : plancher bas — à 15 % ou moins, rejet dur sans appel (la garde
     * bas garde ses dents : les rouges à bruit vert sortent à 0,00 ; la
     * forme tranche dans la bande 15-35 %, les quasi-ratés). 0,15 depuis
     * le 01/11 (ordre user : vert plus facile — `tel_g07` téléphone à
     * 0,23 basculait rouge sur 1 px de jitter de boîte ; bascules 11 → 5
     * sur 31 ancres, 0 rouge bougé).
     */
    const val R1B_BOTTOM_FLOOR = 0.15f

    /**
     * Verdict sûr du crop [argb] ([width] x [height]) : le vert de l'arbre ne
     * passe que la porte, tout le reste vaut [CrossingState.Red] — binaire,
     * un refus attend (ordre user du 31/10, fini le doute).
     */
    fun verdict(argb: IntArray, width: Int, height: Int): CrossingState {
        val raw = TrainedLightColor.classify(argb, width, height)
        if (raw != CrossingState.Green) return CrossingState.Red
        if (acceptGreen(argb, width, height)) return CrossingState.Green
        return CrossingState.Red
    }

    /**
     * Vrai si le crop [argb] ([width] x [height]) montre une lampe allumée :
     * fraction rouge ou verte au plancher [GREEN_MIN_FRACTION]. Garde du
     * repli haut-de-poteau (`CrossingPrompts.selectPoleTopBox`) : un poteau
     * sans feu reste sans boîte (branche sans-feu intacte), seul un poteau
     * à lampe vue devient un rouge d'attente. Public : l'appelant est `:app`.
     */
    fun hasLampSignal(argb: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || argb.size != width * height) return false
        val scan = scan(argb, width, height)
        return (scan.red + scan.green).toFloat() / argb.size >= GREEN_MIN_FRACTION
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
        val wide = boxHeight.toFloat() < boxWidth * ELONGATION_MIN
        if (wide && scan.green.toFloat() > boxWidth * boxHeight * WIDE_FILL_MAX) return false
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

    /** Pixel retenu : la seule définition ([PedLightColor.isLit], francs + délavés). */
    private fun isLit(pixel: Int): Boolean = PedLightColor.isLit(pixel)

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
