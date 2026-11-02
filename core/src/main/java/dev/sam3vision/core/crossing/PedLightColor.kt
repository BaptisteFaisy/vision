package dev.sam3vision.core.crossing

/**
 * Couleur d'un feu piéton lue dans ses pixels : compte les pixels verts et
 * rouges **du cœur brillant de la lampe** et tranche [CrossingState.Red] ou
 * [CrossingState.Green] — binaire, tout non-vert attend (ordre user du 31/10).
 *
 * **Décision par projection chromatique signée, sans borne de teinte**
 * (mesures du 02/11/2026, `.scratch/feux-improve/a8/RAPPORT.md`) : un pixel
 * vote selon `p = (G − R) / max(R, G, B)` — vert si `p ≥ 0,30`, rouge si
 * `p ≤ −0,50`, neutre entre les deux. Une teinte HSV est un angle lu sur une
 * chroma arbitrairement faible : c'est ce qui obligeait la borne haute à 195°
 * (les verts français sont vert-de-mer, 22 à 42 % de leur masse au-dessus de
 * 175°) et faisait entrer les pixels délavés par le soleil (78 % d'entre eux
 * atterrissent à 195-210°, un artefact de bloom, pas une lampe). `p` est
 * borné par construction : +1 vert pur, +0,40 cyan 180°, 0 bleu/blanc/gris,
 * −1 rouge pur — **le bleu tombe à 0, aucune borne haute n'est nécessaire**.
 * À saturation pleine, `p ≥ 0,30` coupe à 78° de teinte (l'ancienne borne 80°
 * reproduite sans la nommer) et refuse une teinte lue sur une chroma faible.
 *
 * Mesuré le 02/11/2026 (harnais JVM hors dépôt, corpus publics de l'axe A8 ;
 * « classifieur » = [classify], « porte » = [PedLightGate.verdict], qui lit la
 * même projection entre-temps) :
 *   - 49 crops téléphone FR (20 V / 29 R) : **20/20 verts, 0 faux vert** au
 *     classifieur (bornes de teinte : 11/20) et 20/20 à la porte (+ 3 disques
 *     `ub7` admis, comme avant) ;
 *   - 33 ancres `{feuxfr,feuxfr2,feuxfr3}` : **33/33 verdicts inchangés**
 *     (22 verts / 11 rouges) à la porte ;
 *   - 280 crops jitterés : **155/240** au classifieur, **160/240** à la porte
 *     (bornes de teinte : 93/240 et 140/240) ;
 *   - PTL (Shanghaï, hors domaine) : 99/184 avec 14 faux verts à la porte
 *     (bornes : 103/184, 14) — coût assumé hors France ;
 *   - photos hors domaine (fenêtres 48 px) : 62 fenêtres « vertes » sur les
 *     négatifs contre 190 (246 → 257 au total, la hausse venant des traversées) ;
 *   - robustesse exposition (gain 1,45 écrêté) : **42/42 verts** sur les 20
 *     verts FR + 22 ancres vertes à la porte, contre 36/42 avec les bornes.
 *
 * Le plancher de pureté est mesuré, pas copié : 5 % du cœur brillant (valeur
 * de l'axe A2) laissait tomber 2 verts FR sur 20 (`0004` à 3,0 % et `0009` à
 * 0,84 % d'un cœur saturé par le ciel) ; à 5 ‰ les 20 passent et le balayage
 * 0,2 % → 1 % ne bouge rien d'autre. La marge 2,5 est inerte sur ces corpus
 * (aucun crop n'a de rouge à moins d'un tiers du vert) : elle ne sert qu'au
 * cas synthétique de match nul.
 *
 * Ce que ce fichier ne touche pas : [CrossingFeatures] et
 * [TrainedLightColor] restent entraînés sur les traits de **teinte** — l'arbre
 * n'est pas réentraîné (contrat séparé). [RED_HUE_DEG], [GREEN_HUE_MIN_DEG] et
 * [GREEN_HUE_MAX_DEG] ne servent donc plus qu'aux traits, et [isLit] à la
 * porte ([PedLightGate]) et au recentrage `:app`.
 *
 * L'appelant a déjà cadré le feu (crop de la boîte SAM, voir `:app`) : ce
 * classifieur ne fait que la couleur, sans Android ni Bitmap — pur Kotlin,
 * testé en JVM sur des pixels synthétiques.
 */
object PedLightColor {

    /** Pixel vert : `(G − R) / max(R,G,B) ≥ 0,30` (78° de teinte à saturation pleine). */
    const val GREEN_PROJECTION_MIN = 0.30f

    /** Pixel rouge : `(G − R) / max(R,G,B) ≤ −0,50` (couvre 65,1 % des px rouges FR contre 52,6 % pour ±15°). */
    const val RED_PROJECTION_MAX = -0.50f

    /** Cœur brillant : pixels à au moins 80 % de la valeur maximale du crop (invariant à l'exposition). */
    internal const val BRIGHT_BAND_PERCENT = 80

    /** Valeur (max R,G,B sur 255) sous laquelle le crop est éteint ou nocturne : 77 = 30 %. */
    const val MIN_VALUE_BYTE = 77

    /** En dessous de 6 pixels, la bande n'est pas une lampe : repli sur le crop entier. */
    internal const val MIN_BAND_PX = 6

    /** Un verdict ne se prend pas sur un ou deux pixels (bande dégénérée). */
    const val MIN_GREEN_PX = 3

    /** Pureté minimale : 5 ‰ du cœur brillant doit voter vert (plancher de bruit, mesuré — voir KDoc de tête). */
    const val MIN_GREEN_FRACTION = 0.005f

    /** Le vert doit dominer le rouge d'autant : pas de verdict sur un match nul. */
    const val MARGIN_RATIO = 2.5f

    /** Teinte rouge des **traits** de [CrossingFeatures] seulement (plus de rôle dans la décision). */
    const val RED_HUE_DEG = 15f

    /** Plage de teinte verte des **traits** de [CrossingFeatures] seulement (LED françaises ≤ 195°). */
    internal const val GREEN_HUE_MIN_DEG = 80f
    internal const val GREEN_HUE_MAX_DEG = 195f

    /** Saturation et valeur minimales d'un pixel de lampe franc. */
    const val MIN_SATURATION = 0.45f
    const val MIN_VALUE = 0.35f

    /** Délavage du jour : une lampe surexposée (valeur ≥ 0,90) compte dès 0,15 de saturation — les gris (sat 0) restant exclus. */
    internal const val WASHOUT_MIN_SATURATION = 0.15f
    internal const val WASHOUT_MIN_VALUE = 0.90f

    /**
     * État du feu cadré par [argb] ([width] × [height], ARGB 32 bits, ligne
     * majeure). Tableau vide ou dimensions incohérentes ⇒ [CrossingState.Red]
     * (une entrée illisible attend, comme un doute). Noir, gris, blanc et
     * surexposé ⇒ `p ≈ 0` ⇒ aucun vote ⇒ [CrossingState.Red].
     */
    fun classify(argb: IntArray, width: Int, height: Int): CrossingState {
        if (width <= 0 || height <= 0 || argb.size != width * height) return CrossingState.Red
        val maxByte = maxByte(argb)
        if (maxByte < MIN_VALUE_BYTE) return CrossingState.Red
        val tally = IntArray(3)
        scan(argb, maxByte * BRIGHT_BAND_PERCENT / 100, tally)
        if (tally[2] < MIN_BAND_PX) scan(argb, 1, tally)
        val green = tally[0]
        val red = tally[1]
        val band = tally[2]
        val enough = green >= MIN_GREEN_PX && green >= MIN_GREEN_FRACTION * band
        return if (enough && green >= MARGIN_RATIO * red) CrossingState.Green
        else CrossingState.Red
    }

    /** Valeur maximale (0..255) du crop : référence du cœur brillant, une passe sans allocation. */
    private fun maxByte(argb: IntArray): Int {
        var max = 0
        for (pixel in argb) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val v = if (r > g) maxOf(r, b) else maxOf(g, b)
            if (v > max) max = v
        }
        return max
    }

    /**
     * Compte, parmi les pixels de valeur ≥ [floorByte], les verts, les rouges et
     * la taille du cœur — écrit dans [tally] = (verts, rouges, cœur). Un seul
     * parcours, aucune allocation par pixel : [tally] est réutilisé par l'appelant.
     */
    private fun scan(argb: IntArray, floorByte: Int, tally: IntArray) {
        var green = 0
        var red = 0
        var band = 0
        for (pixel in argb) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (maxOf(r, maxOf(g, b)) < floorByte) continue
            band += 1
            val projection = projection(g, r, b)
            if (projection >= GREEN_PROJECTION_MIN) {
                green += 1
            } else if (projection <= RED_PROJECTION_MAX) {
                red += 1
            }
        }
        tally[0] = green
        tally[1] = red
        tally[2] = band
    }

    /**
     * `(G − R) / max(R,G,B)` : +1 vert pur, +0,40 cyan 180°, 0 bleu/gris/blanc,
     * −1 rouge pur. Borné [-1,1] et sûr à `max = 0` (pixel noir : neutre).
     */
    private fun projection(g: Int, r: Int, b: Int): Float {
        val max = maxOf(r, maxOf(g, b))
        if (max <= 0) return 0f
        return (g - r).toFloat() / max
    }

    /** Projection d'un pixel ARGB : lecture unique, une division — sans conversion HSV. */
    internal fun projectionOf(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        return projection(g, r, pixel and 0xFF)
    }

    /**
     * Pixel de lampe allumée vu par [PedLightGate] (traits, porte) : franc
     * (saturation ≥ [MIN_SATURATION]) ou délavé par le soleil (très brillant,
     * saturation ≥ [WASHOUT_MIN_SATURATION]) — une lampe blanche de jour reste
     * une lampe. Toujours éteint sous [MIN_VALUE]. `classify` ne l'utilise plus
     * (le cœur brillant joue ce rôle : une lampe pâle garde un `p` mesurable au
     * lieu d'une teinte de bruit), mais la porte et le recentrage si — définition
     * publique inchangée, donc aucun de leurs verdicts ne bouge.
     */
    internal fun isLit(pixel: Int): Boolean {
        val value = value(pixel)
        if (value < MIN_VALUE) return false
        val sat = saturation(pixel)
        if (sat >= MIN_SATURATION) return true
        return sat >= WASHOUT_MIN_SATURATION && value >= WASHOUT_MIN_VALUE
    }

    /**
     * Pixel de lampe allumée vu de `:app` (recentrage) : le même
     * prédicat que [isLit], exposé public — une seule définition.
     */
    fun isLampPixel(pixel: Int): Boolean = isLit(pixel)

    /** Teinte 0..360° : **traits** de [CrossingFeatures] et porte seulement, plus la décision. */
    internal fun hueDeg(pixel: Int): Float {
        val r = ((pixel shr 16) and 0xFF) / 255f
        val g = ((pixel shr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta <= 0f) return 0f
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    internal fun saturation(pixel: Int): Float {
        val r = ((pixel shr 16) and 0xFF) / 255f
        val g = ((pixel shr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val max = maxOf(r, g, b)
        if (max <= 0f) return 0f
        return (max - minOf(r, g, b)) / max
    }

    internal fun value(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return maxOf(r, g, b) / 255f
    }
}
