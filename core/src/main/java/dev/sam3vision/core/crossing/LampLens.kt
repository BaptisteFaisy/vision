package dev.sam3vision.core.crossing

/**
 * Flux lumineux de la **lentille** d'un crop de feu piéton : la tache allumée
 * la plus brillante, les points de LED recousus par fermeture morphologique.
 *
 * Mesuré le 02/11/2026 sur 7 photos étiquetées (`a4/RAPPORT.md` §6, §9.1) : le
 * **score SAM** préfère la lanterne **éteinte** du haut (0,84 sur le rouge
 * éteint contre 0,77 sur le vert allumé) — il classe donc la mauvaise boîte ;
 * le flux de lentille, lui, sépare 16 364 (lampe allumée) de 73 (lanterne
 * éteinte) et rend 6/6 verts évaluables, 0 faux vert, 31/31 ancres intactes.
 * C'est un **départage entre détections plausibles**, pas un détecteur : le
 * score garde son plancher (0,50 stable).
 *
 * Pur Kotlin (aucun import, aucun Android) : entrée = pixels ARGB d'un crop,
 * sortie = un `Float`. Une **seule** allocation temporaire (le tableau d'état,
 * réutilisé par toutes les passes), aucune récursion.
 */
object LampLens {

    /**
     * Rayon de fermeture, en fraction du plus grand côté : recoud les points de
     * LED d'une même lentille (bonhomme, ampoule à facettes). Robuste de 0,05 à
     * 0,30 dans la mesure ; r = 0,20 est le seul décrochage (5/7 verts).
     */
    const val CLOSE_FRACTION = 0.10f

    /**
     * Flux minimal d'une vraie lampe allumée (value × saturation cumulés) : en
     * dessous c'est un reflet, une LED lointaine ou du bruit de compression.
     */
    const val MIN_FLUX = 1f

    /** Bits d'état d'un pixel : allumé, deux dilatations, deux érosions, marque. */
    private const val LIT = 1
    private const val DILATED_ROW = 2
    private const val DILATED_COL = 4
    private const val ERODED_ROW = 8
    private const val ERODED_COL = 16
    private const val MARK = 32

    /** Bits 0..5 = état, bits 6..31 = maillon de pile (indice + 1) : pile gratuite. */
    private const val STATE_BITS = 0x3F
    private const val LINK_SHIFT = 6

    /**
     * Flux de la lentille la plus lumineuse de [argb] ([width] × [height], ARGB
     * 32 bits, ligne majeure) ; 0 si aucune lampe. Pixels allumés =
     * [PedLightColor.isLampPixel], recousus par fermeture de rayon
     * `CLOSE_FRACTION × max(côtés)` (dilatations puis érosions séparables ligne
     * et colonne, bords hors cadre comptés éteints, comme la référence), taches
     * étiquetées en 8-connexe, flux d'une tache = somme des (value × saturation)
     * de ses pixels allumés, on renvoie le **maximum**. [MIN_FLUX] reste au
     * appelant : cette fonction ne juge pas, elle note.
     *
     * Entrée vide ou dimensions incohérentes ⇒ 0 (rien à noter). Le tableau
     * d'entrée n'est pas modifié. Un crop de 1 × 1 allumé rend 0 : la fermeture
     * érode les bords, la lentille n'a pas de centre — même résultat que
     * `a4/lens.py`.
     */
    fun flux(argb: IntArray, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0 || argb.size != width * height) return 0f
        val state = IntArray(argb.size)
        for (i in argb.indices) {
            if (PedLightColor.isLampPixel(argb[i])) state[i] = LIT
        }
        close(state, width, height, radiusOf(width, height))
        return brightestLens(argb, state, width, height)
    }

    /**
     * Fermeture séparable : dilatation ligne, dilatation colonne, érosion ligne,
     * érosion colonne — le carré (2k+1)² se factorise exactement en ces deux
     * axes, donc le résultat est identique à un élément structurant carré.
     */
    private fun close(state: IntArray, width: Int, height: Int, k: Int) {
        sweep(state, height, width, width, 1, k, LIT, DILATED_ROW, erase = false)
        sweep(state, width, height, 1, width, k, DILATED_ROW, DILATED_COL, erase = false)
        fill(state, ERODED_ROW)
        sweep(state, height, width, width, 1, k, DILATED_COL, ERODED_ROW, erase = true)
        fill(state, ERODED_COL)
        sweep(state, width, height, 1, width, k, ERODED_ROW, ERODED_COL, erase = true)
    }

    /**
     * Rayon de fermeture en pixels : `round(0,10 × plus grand côté)`, arrondi au
     * pair le plus proche (comme le `round` de la référence Python), au moins 1.
     */
    private fun radiusOf(width: Int, height: Int): Int =
        maxOf(1, Math.rint(CLOSE_FRACTION.toDouble() * maxOf(width, height)).toInt())

    /**
     * Fenêtre glissante 1D le long d'un axe : pose ([erase] faux) ou efface
     * ([erase] vrai) [dst] sur `[i-k, i+k]` pour chaque pixel dont [src] est
     * vrai, les bords hors cadre comptant faux. C'est le **max-glissant** de la
     * dilatation ; l'**érosion** est le même balayage sur le complément
     * (fenêtres des pixels éteints effacées d'un masque d'abord rempli). `end`
     * saute les positions déjà couvertes : O(n), aucun tableau temporaire.
     *
     * Les axes sont décrits par des pas d'indice ([outerStride], [innerStride])
     * pour que la ligne et la colonne partagent ce seul balayage.
     */
    private fun sweep(state: IntArray, outer: Int, inner: Int, outerStride: Int,
                      innerStride: Int, k: Int, src: Int, dst: Int, erase: Boolean) {
        val first = if (erase) -1 else 0
        val last = if (erase) inner else inner - 1
        for (o in 0 until outer) {
            val base = o * outerStride
            var end = 0
            for (i in first..last) {
                val on = if (i < 0 || i >= inner) erase
                else ((state[base + i * innerStride] and src) != 0) != erase
                if (!on) continue
                var from = maxOf(i - k, end, 0)
                val to = minOf(i + k + 1, inner)
                var j = base + from * innerStride
                while (from < to) {
                    // En phase de fermeture les bits ≥ 6 sont libres : `dst.inv()`
                    // n'efface donc que l'état qu'on vient d'initialiser.
                    state[j] = if (erase) state[j] and dst.inv() else state[j] or dst
                    j += innerStride
                    from++
                }
                if (to > end) end = to
            }
        }
    }

    /** Allume [bit] partout : l'érosion part d'un masque plein et le vide. */
    private fun fill(state: IntArray, bit: Int) {
        for (i in state.indices) state[i] = state[i] or bit
    }

    /**
     * Plus fort flux de tache fermée : parcours des composantes 8-connexes du
     * masque [ERODED_COL], flux cumulé sur les seuls pixels [LIT].
     *
     * La pile du remplissage vit dans les bits de poids fort du tableau d'état
     * (liste chaînée intrusive, sommet = indice) : une seule allocation pour
     * tout `flux`, alimentation en lecture, marquage à l'empilement (un pixel
     * n'est empilé qu'une fois), donc O(n) et pas de récursion.
     */
    private fun brightestLens(argb: IntArray, state: IntArray, width: Int, height: Int): Float {
        var best = 0f
        for (start in argb.indices) {
            if ((state[start] and (ERODED_COL or MARK)) != ERODED_COL) continue
            var head = -1
            var sum = 0f
            state[start] = (state[start] and STATE_BITS) or MARK or ((head + 1) shl LINK_SHIFT)
            head = start
            while (head >= 0) {
                val i = head
                head = (state[i] ushr LINK_SHIFT) - 1
                state[i] = state[i] and STATE_BITS
                if ((state[i] and LIT) != 0) {
                    val pixel = argb[i]
                    sum += PedLightColor.value(pixel) * PedLightColor.saturation(pixel)
                }
                val y = i / width
                val x = i - y * width
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val j = ny * width + nx
                        if ((state[j] and (ERODED_COL or MARK)) != ERODED_COL) continue
                        state[j] = (state[j] and STATE_BITS) or MARK or ((head + 1) shl LINK_SHIFT)
                        head = j
                    }
                }
            }
            if (sum > best) best = sum
        }
        return best
    }
}
