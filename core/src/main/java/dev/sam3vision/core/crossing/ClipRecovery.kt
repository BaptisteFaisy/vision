package dev.sam3vision.core.crossing

import dev.sam3vision.core.exposure.ClipStats

/**
 * Récupération d'un feu piéton **écrasé par la surexposition** : les pixels
 * brûlés gardent une teinte faible mais signée, et cette teinte suffit à
 * sauver une frame que [PedLightGate] refuse.
 *
 * Le défaut mesuré (2/11/2026, 58 séquences de rue, 12 503 frames) : **33,2 %**
 * des frames ont la lampe écrêtée (canal max ≥ [ClipStats.CLIP_LEVEL]) et
 * 10 verts réels sur 58 séquences ne sont jamais annoncés. Sur les **371**
 * frames de phase verte réelle que le pipeline rend `Red` (52 séquences dont la
 * boîte suit bien le feu), **359 (97 %)** tombent sur la clause « tache large et
 * pleine » de [PedLightGate] : la surexposition élargit et remplit le bonhomme
 * (remplissage mesuré 0,51 à 0,78), qui devient un disque aux yeux de la garde.
 * Le cœur écrasé, lui, garde la silhouette (remplissage 0,11 à 0,38) : c'est ce
 * que lit cette règle.
 *
 * Pourquoi la projection survit à l'écrasement (mesuré, pixels écrasés) :
 * une LED verte sature le canal G à 255 alors que R reste ~230, donc
 * (G − R) / max ≈ **+0,10** ; une LED rouge sature R et la projection reste
 * **négative**. Sur 1 460 frames de phase verte et 8 465 de phase rouge (52
 * séquences ; 217 272 px écrasés verts contre 1 170 222 rouges) : AUC **0,991**
 * et d' **2,34** pour les pixels écrasés, contre 0,994 et 4,71 pour les francs. La teinte est encore lisible ; c'est le seuil
 * [PedLightColor.GREEN_PROJECTION_MIN] (0,30) qui la jetait.
 *
 * La règle est **conservatrice** et ne peut pas casser un cas correct :
 * 1. **Priorité au chemin normal** — un crop déjà `Green` n'est pas retouché ;
 *    un crop dont les pixels non écrasés prouvent le rouge (fraction ≥
 *    [PedLightGate.RED_MAX_FRACTION]) non plus.
 * 2. **Plancher de fiabilité** — il faut assez de pixels verts écrasés
 *    ([floorPixels] : 8 px, ou 0,15 % du crop) **et** trois fois plus de verts
 *    que de rouges côté écrasé (une lampe rouge brûlée n'a aucun pixel vert).
 * 3. **Garde de forme** — mêmes seuils que [PedLightGate] sur la tache écrasée :
 *    au moins [BBOX_MIN_PX] de haut, large d'au moins
 *    [GREEN_MIN_WIDTH_FRACTION] du crop, pas un disque plein plus large que
 *    haut, pas confinée en haut du cadre.
 *
 * Recette (2/11/2026, `fast.py` sur 58 séquences) : 52 séquences hors faute de
 * boîte, rappel vert **74,0 % → 99,0 %** (+365 frames), **0 faux vert ajouté**
 * (2 frames de part et d'autre, toutes deux à 0,4 s de la bascule réelle) ;
 * 6 séquences passent de « vert raté » à « vert trouvé ». Sur les 6 séquences
 * où la boîte suivait un autre feu, +5 frames de faux vert (1,0 s), défaut de
 * sélection de boîte que cette couche ne prétend pas corriger.
 * Ancres intactes : 33/33 `feuxfr*`, 49 crops téléphone 20/20 verts et
 * 0 faux vert (la règle n'y déclenche jamais : aucun de ces crops n'est écrasé
 * ET refusé).
 *
 * Pur Kotlin (aucun Android), trois tableaux bornés par le crop (le masque
 * écrasé, puis `seen` et la pile de [widestComponent]) — 96 × 96 au maximum en
 * production, aucune récursion, aucune collection.
 * Miroir Python : `clip_recovery.py` (mêmes seuils, même ordre d'opérations ;
 * les dix cas synthétiques de `ClipRecoveryTest` rendent le même verdict des
 * deux côtés).
 */
object ClipRecovery {

    /** Projection (G − R) / max d'un pixel écrasé encore vert (mesurée : +0,10 en médiane). */
    const val RECOVER_GREEN_MIN = 0.08f

    /** Côté rouge, symétrique : une LED rouge écrasée reste sous 0 (mesurée : −0,01). */
    const val RECOVER_RED_MAX = -0.08f

    /** Verts écrasés exigés par rouge écrasé : le rouge brûlé n'a aucun pixel vert. */
    const val MARGIN_RATIO = 3.0f

    /** Plancher absolu de pixels verts écrasés (une LED lointaine à 48 px : 4 à 9 px). */
    const val MIN_PIXELS = 8

    /** Plancher relatif : 0,15 % du crop (14 px sur 96 × 96). */
    const val MIN_FRACTION = 0.0015f

    /** Hauteur minimale de la tache écrasée, en pixels ([PedLightGate.BBOX_MIN_PX]). */
    private const val BBOX_MIN_PX = 3

    /** Remplissage max d'une tache plus large que haute ([PedLightGate] : disque plein). */
    private const val WIDE_FILL_MAX = 0.35f

    /** Largeur minimale de la plus large composante, en part du crop ([PedLightGate]). */
    private const val GREEN_MIN_WIDTH_FRACTION = 0.04f

    /** Part basse minimale de la tache ([PedLightGate.R1B_BOTTOM_FLOOR]). */
    private const val BOTTOM_FLOOR = 0.15f

    /**
     * Verdict final : [gate] s'il est déjà `Green` (le chemin normal reste la
     * référence, on ne le retouche jamais), sinon `Green` seulement si
     * [recover] l'autorise. Tout le reste attend ([CrossingState.Red]).
     */
    fun verdict(
        gate: CrossingState,
        argb: IntArray,
        width: Int,
        height: Int,
        redFraction: Float,
    ): CrossingState {
        if (gate == CrossingState.Green) return CrossingState.Green
        return if (recover(argb, width, height, redFraction).recovered) {
            CrossingState.Green
        } else {
            CrossingState.Red
        }
    }

    /**
     * Décision de récupération seule (le verdict du chemin normal est déjà
     * `Red` chez l'appelant), avec le détail qui explique le refus — journalisé
     * en recette, jamais lu par la décision.
     */
    fun recover(argb: IntArray, width: Int, height: Int, redFraction: Float): Result {
        val total = width * height
        if (width <= 0 || height <= 0 || argb.size < total) {
            return Result(0, 0, floorPixels(0), false, REASON_UNREADABLE)
        }
        if (redFraction >= PedLightGate.RED_MAX_FRACTION) {
            return Result(0, 0, floorPixels(total), false, REASON_RED_PROVEN)
        }
        val scan = scanClipped(argb, width, height)
        val floor = floorPixels(total)
        if (scan.green < floor) {
            return Result(scan.green, scan.red, floor, false, REASON_FLOOR)
        }
        if (scan.green < MARGIN_RATIO * scan.red) {
            return Result(scan.green, scan.red, floor, false, REASON_MARGIN)
        }
        if (!shapedLikeLamp(scan, width, height)) {
            return Result(scan.green, scan.red, floor, false, scan.reason)
        }
        return Result(scan.green, scan.red, floor, true, REASON_RECOVERED)
    }

    /**
     * Plancher de pixels verts écrasés : [MIN_PIXELS] ou [MIN_FRACTION] du crop,
     * le plus grand des deux — un crop 96 × 96 exige 14 px, un crop 48 px en
     * exige 8 (une LED écrasée y fait 4 à 9 px : le plancher relatif seul la
     * perdrait, le plancher absolu seul laisserait passer du bruit à 96 px).
     */
    fun floorPixels(total: Int): Int {
        if (total <= 0) return MIN_PIXELS
        val relative = (MIN_FRACTION * total + 0.5f).toInt()
        return if (relative < MIN_PIXELS) MIN_PIXELS else relative
    }

    /**
     * Une passe sur le crop : compte les pixels **écrasés** (canal max ≥
     * [ClipStats.CLIP_LEVEL]) de chaque camp et retient la boîte englobante et
     * la part basse du camp vert. Allocation : le masque vert, un octet par
     * pixel (la seule que la règle ajoute au crop).
     */
    private fun scanClipped(argb: IntArray, width: Int, height: Int): Scan {
        val total = width * height
        val mask = ByteArray(total)
        var green = 0
        var red = 0
        var greenBottom = 0
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        val half = height / 2
        for (index in 0 until total) {
            val pixel = argb[index]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val max = maxOf(r, maxOf(g, b))
            if (max < ClipStats.CLIP_LEVEL) continue
            // max ≥ 250 : jamais de division par zéro.
            val projection = (g - r).toFloat() / max
            if (projection >= RECOVER_GREEN_MIN) {
                green += 1
                mask[index] = 1
                val y = index / width
                val x = index - y * width
                if (y >= half) greenBottom += 1
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            } else if (projection <= RECOVER_RED_MAX) {
                red += 1
            }
        }
        return Scan(green, red, greenBottom, minX, maxX, minY, maxY, mask, "")
    }

    /**
     * Garde de forme de la tache écrasée (mêmes seuils que [PedLightGate], lus
     * sur le masque écrasé et non sur le masque délavé) : c'est elle qui écarte
     * le disque de feu véhicule et la tache d'un pixel.
     */
    private fun shapedLikeLamp(scan: Scan, width: Int, height: Int): Boolean {
        if (scan.green <= 0) {
            scan.reason = REASON_NARROW
            return false
        }
        val boxWidth = scan.maxX - scan.minX + 1
        val boxHeight = scan.maxY - scan.minY + 1
        if (boxHeight < BBOX_MIN_PX) {
            scan.reason = REASON_NARROW
            return false
        }
        if (boxHeight < boxWidth && scan.green > boxWidth * boxHeight * WIDE_FILL_MAX) {
            scan.reason = REASON_DISC
            return false
        }
        if (widestComponent(scan.mask, width, height) <
            GREEN_MIN_WIDTH_FRACTION * maxOf(width, height)
        ) {
            scan.reason = REASON_NARROW
            return false
        }
        if (scan.greenBottom.toFloat() / scan.green < BOTTOM_FLOOR) {
            scan.reason = REASON_TOP
            return false
        }
        return true
    }

    /**
     * Étendue X de la plus large composante 8-connexe du masque (pile
     * explicite, aucune récursion) — même mesure que
     * [PedLightGate.widestComponent], appliquée au masque écrasé.
     */
    private fun widestComponent(mask: ByteArray, width: Int, height: Int): Int {
        val seen = ByteArray(mask.size)
        val stack = IntArray(mask.size)
        var best = 0
        for (start in mask.indices) {
            if (mask[start].toInt() == 0 || seen[start].toInt() != 0) continue
            seen[start] = 1
            var lo = start % width
            var hi = lo
            var top = 1
            stack[0] = start
            while (top > 0) {
                val index = stack[--top]
                val y = index / width
                val x = index - y * width
                if (x < lo) lo = x
                if (x > hi) hi = x
                var dy = -1
                while (dy <= 1) {
                    val ny = y + dy
                    dy += 1
                    if (ny < 0 || ny >= height) continue
                    val base = ny * width
                    var dx = -1
                    while (dx <= 1) {
                        val nx = x + dx
                        dx += 1
                        if (nx < 0 || nx >= width) continue
                        val n = base + nx
                        if (mask[n].toInt() != 0 && seen[n].toInt() == 0) {
                            seen[n] = 1
                            stack[top++] = n
                        }
                    }
                }
            }
            if (hi - lo + 1 > best) best = hi - lo + 1
        }
        return best
    }

    /** Comptes d'une frame écrasée ; [reason] n'est renseigné qu'en cas de refus. */
    private class Scan(
        val green: Int,
        val red: Int,
        val greenBottom: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val mask: ByteArray,
        var reason: String,
    )

    /** Issue de [recover] : `recovered` est la seule chose que lit la décision. */
    data class Result(
        /** Pixels verts écrasés (projection ≥ [RECOVER_GREEN_MIN]). */
        val green: Int,
        /** Pixels rouges écrasés (projection ≤ [RECOVER_RED_MAX]). */
        val red: Int,
        /** Plancher de pixels appliqué à cette frame. */
        val floor: Int,
        /** Vrai : la frame est sauvée ([CrossingState.Green] autorisé). */
        val recovered: Boolean,
        /** Motif lisible (`lampe ecrasee lisible`, `disque plein`, …), pour le journal. */
        val reason: String,
    )

    private const val REASON_RECOVERED = "lampe ecrasee lisible"
    private const val REASON_RED_PROVEN = "rouge prouve (chemin normal)"
    private const val REASON_FLOOR = "sous le plancher de pixels"
    private const val REASON_MARGIN = "marge verte insuffisante"
    private const val REASON_UNREADABLE = "crop illisible"
    private const val REASON_DISC = "disque plein"
    private const val REASON_NARROW = "tache trop etroite"
    private const val REASON_TOP = "tache confinee en haut"
}
