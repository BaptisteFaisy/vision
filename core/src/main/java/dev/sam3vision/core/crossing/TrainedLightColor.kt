package dev.sam3vision.core.crossing

/**
 * Classifieur d'état entraîné hors dépôt sur les crops du jeu Ampel
 * (dépôt patVlnta/Ampel-Pilot-Dataset : boîtes SQL, feux allemands), puis
 * porté ici sous forme de tableaux. Ne pas éditer à la main : régénérer
 * depuis les crops (harnais d'évaluation), mêmes hyperparamètres
 * (profondeur 6, feuille min 20). Traits : [FEATURE_NAMES] dans l'ordre.
 *
 * Sous le plancher de pureté de la feuille atteinte, la réponse est
 * [CrossingState.Unknown] : un doute vaut mieux qu'une couleur fragile.
 * Le vert se tranche plus large ([GREEN_MIN_PURITY]) que le rouge
 * ([MIN_PURITY]) — un vert raté fait attendre, mais un vert jamais détecté
 * ne sert à rien — avec un garde-fou : sans pixels verts
 * ([PedLightGate.GREEN_MIN_FRACTION]), pas de verdict vert (le noir reste
 * un doute). Reste à valider sur feux français et boîtes SAM réelles
 * (non mesuré).
 */
object TrainedLightColor {

    /** Pureté minimale de la feuille pour trancher rouge (sinon doute). */
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

    private val FEATURE = intArrayOf(3, 8, 15, 15, 15, 16, -2, -2, -2, -2, 10, 15, -2, 15, -2, -2, -2, 15, 0, -2, -2, -2, 10, -2, -2)
    private val THRESHOLD = floatArrayOf(0.020556f, 0.000737f, 0.251779f, 0.235394f, 0.179629f, 0.285050f, -2.000000f, -2.000000f, -2.000000f, -2.000000f, 0.000336f, 0.302868f, -2.000000f, 0.557728f, -2.000000f, -2.000000f, -2.000000f, 0.483485f, 0.966917f, -2.000000f, -2.000000f, -2.000000f, 0.001128f, -2.000000f, -2.000000f)
    private val LEFT = intArrayOf(1, 2, 3, 4, 5, 6, -1, -1, -1, -1, 11, 12, -1, 14, -1, -1, -1, 18, 19, -1, -1, -1, 23, -1, -1)
    private val RIGHT = intArrayOf(22, 17, 10, 9, 8, 7, -1, -1, -1, -1, 16, 13, -1, 15, -1, -1, -1, 21, 20, -1, -1, -1, 24, -1, -1)
    private val LEAF_CLASS = intArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1)
    private val LEAF_PURITY = floatArrayOf(0.6256f, 0.9034f, 0.5590f, 0.5970f, 0.5614f, 0.6250f, 0.7179f, 0.5366f, 0.5882f, 0.8000f, 0.6136f, 0.5994f, 0.7895f, 0.5772f, 0.5030f, 0.6624f, 0.8571f, 0.9974f, 0.9995f, 1.0000f, 0.9500f, 0.8000f, 0.9963f, 1.0000f, 0.8519f)

    /** Noms des traits, dans l'ordre d'extraction (documentation et tests). */
    val FEATURE_NAMES: List<String> = listOf("hue_bin_0", "hue_bin_1", "hue_bin_2", "hue_bin_3", "hue_bin_4", "hue_bin_5", "hue_bin_6", "hue_bin_7", "red_top", "red_bottom", "green_top", "green_bottom", "red_frac", "green_frac", "sat_frac", "mean_value", "mean_sat")

    /**
     * État du feu cadré par [argb] ([width] x [height], ARGB 32 bits).
     * Tableau vide ou incohérent ⇒ [CrossingState.Unknown], comme HSV.
     */
    fun classify(argb: IntArray, width: Int, height: Int): CrossingState {
        if (width <= 0 || height <= 0 || argb.size != width * height) return CrossingState.Unknown
        val features = CrossingFeatures.extract(argb, width, height)
        var node = 0
        while (LEFT[node] >= 0) {
            node = if (features[FEATURE[node]] <= THRESHOLD[node]) LEFT[node] else RIGHT[node]
        }
        val leafGreen = LEAF_CLASS[node] == 1
        val floor = if (leafGreen) GREEN_MIN_PURITY else MIN_PURITY
        if (LEAF_PURITY[node] < floor) return CrossingState.Unknown
        if (leafGreen && features[GREEN_FRAC_INDEX] < PedLightGate.GREEN_MIN_FRACTION) {
            return CrossingState.Unknown
        }
        return if (leafGreen) CrossingState.Green else CrossingState.Red
    }
}
