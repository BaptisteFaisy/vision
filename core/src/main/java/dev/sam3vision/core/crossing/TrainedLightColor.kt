package dev.sam3vision.core.crossing

/**
 * Classifieur d'état entraîné sur crops téléphone (`tools/train_light.py`,
 * lot lotfinal 52 crops + `fr_green_fig_243` miné en dur positif petit-vert,
 * gini, profondeur 5, feuille min 5, seed 42), porté ici sous forme de
 * tableaux. Ne pas éditer à la main : régénérer depuis les crops, mêmes
 * hyperparamètres. Traits : [FEATURE_NAMES] dans l'ordre.
 *
 * L'arbre appris est une souche (`green_frac` > 0,0035, ordre user du 01/11 : 0,48 % → 0,35 %) : sur les crops
 * téléphone, la fraction verte sépare à elle seule (verts ≥ 0,017, rouges
 * ≤ 0,0018) et la forme reste du ressort de la porte. Mesuré 01/11 :
 * verdicts 18/20 verts lotfinal au seuil 0,0048, à remesurer (0009 et 0015 : tache trop large, élancement
 * < 1,0 — seuils de porte intacts, non réglés), 32/32 rouges, 0 faux vert ;
 * 18/20 ancres stables (2 disques voiture repassent rouges, sens sûr).
 *
 * Sous le plancher de pureté de la feuille atteinte, la réponse est
 * [CrossingState.Red] : binaire, une couleur fragile attend (ordre user du
 * 31/10, fini le doute). Le vert se tranche plus large ([GREEN_MIN_PURITY])
 * que le rouge ([MIN_PURITY]) — avec un garde-fou : sans pixels verts
 * ([PedLightGate.GREEN_MIN_FRACTION]), pas de verdict vert (le noir attend).
 */
object TrainedLightColor {

    /** Pureté minimale exigée d'une feuille rouge (une feuille fragile attend quand même : binaire). */
    const val MIN_PURITY = 0.80f

    /**
     * Pureté minimale de la feuille pour trancher vert (30/10/2026, ordres
     * user : sensibilité du vert augmentée, puis degré de certitude baissé
     * — plancher passé de 0,65 à 0,50). Plus bas que [MIN_PURITY] : les
     * feuilles vertes entre les deux planchers (0,54 et 0,50) sont
     * acceptées ; la porte anti-confusion tranche ensuite.
     */
    const val GREEN_MIN_PURITY = 0.50f

    /** Indice du trait `green_frac` dans [FEATURE_NAMES] (plancher vert). */
    private const val GREEN_FRAC_INDEX = 13

    private val FEATURE = intArrayOf(13, -2, -2)
    private val THRESHOLD = floatArrayOf(0.0035f, -2.0f, -2.0f)
    private val LEFT = intArrayOf(1, -1, -1)
    private val RIGHT = intArrayOf(2, -1, -1)
    private val LEAF_CLASS = intArrayOf(0, 0, 1)
    private val LEAF_PURITY = floatArrayOf(0.0f, 1.0f, 1.0f)

    /** Noms des traits, dans l'ordre d'extraction (documentation et tests). */
    val FEATURE_NAMES: List<String> = listOf("hue_bin_0", "hue_bin_1", "hue_bin_2", "hue_bin_3", "hue_bin_4", "hue_bin_5", "hue_bin_6", "hue_bin_7", "red_top", "red_bottom", "green_top", "green_bottom", "red_frac", "green_frac", "sat_frac", "mean_value", "mean_sat")

    /**
     * État du feu cadré par [argb] ([width] x [height], ARGB 32 bits).
     * Tableau vide ou incohérent ⇒ [CrossingState.Red], comme HSV.
     */
    fun classify(argb: IntArray, width: Int, height: Int): CrossingState {
        if (width <= 0 || height <= 0 || argb.size != width * height) return CrossingState.Red
        val features = CrossingFeatures.extract(argb, width, height)
        var node = 0
        while (LEFT[node] >= 0) {
            node = if (features[FEATURE[node]] <= THRESHOLD[node]) LEFT[node] else RIGHT[node]
        }
        val leafGreen = LEAF_CLASS[node] == 1
        val floor = if (leafGreen) GREEN_MIN_PURITY else MIN_PURITY
        if (LEAF_PURITY[node] < floor) return CrossingState.Red
        if (leafGreen && features[GREEN_FRAC_INDEX] < PedLightGate.GREEN_MIN_FRACTION) {
            return CrossingState.Red
        }
        return if (leafGreen) CrossingState.Green else CrossingState.Red
    }
}
