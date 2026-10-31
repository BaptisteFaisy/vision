package dev.sam3vision.core.distance

import java.text.Normalizer

/**
 * Hauteurs réelles typiques, en mètres, par concept.
 *
 * Le direct suit des concepts **saisis par l'utilisateur** : la table couvre les
 * concepts par défaut calibrables au sténopé et leurs synonymes
 * français/anglais. Un concept absent reste **inconnu** — mieux vaut ne rien
 * afficher qu'un chiffre faux. Les valeurs sont des ordres de grandeur (un
 * humain adulte, une voiture de tourisme), pas des mesures : l'estimation
 * monoculaire reste à ±15-20 %.
 *
 * Pas d'entrée escalier, volontairement : SAM segmente l'escalier **entier**
 * (« stairs »), haut d'un mètre à plusieurs étages selon la volée — aucune
 * hauteur typique n'est défendable, et la marche de 0,17 m ne vaut que pour
 * une marche isolée, jamais produite par ce concept. Même traitement pour
 * « sidewalk » et « crosswalk » (plans au sol, pas de hauteur) et « pole »
 * (de la borne de 1 m au pylône de 10 m selon le mobilier). Même traitement
 * pour « curb » (bordure de 10-15 cm vue sous tous les angles : aucune
 * hauteur typique projetable, le `CurbWatcher` lit la profondeur). La
 * distance de ces défauts vient de la branche profondeur (`DepthReadings`)
 * ou du plan de sol, jamais du sténopé (voir [DEPTH_ONLY] et `StairConcepts`).
 */
object KnownSizes {

    /**
     * Hauteur réelle typique de [label], `null` si le concept n'est pas calibré.
     * Tolérant : casse, accents, espaces et pluriel simple sont neutralisés.
     */
    fun heightMeters(label: String): Float? {
        val key = canonical(label)
        if (key.isEmpty()) return null
        TABLE[key]?.let { return it }
        if (key.endsWith("s") && key.length > 3) TABLE[key.dropLast(1)]?.let { return it }
        return FIRST_WORD[key.substringBefore(' ')]?.takeIf { key.contains(' ') }
    }

    /**
     * Incertitude relative de la hauteur typique de [label] (± fraction) : un adulte
     * debout varie peu (±6 %), un arbre ou un animal beaucoup (±30 %). Même repli que
     * [heightMeters] (pluriel, premier mot), [DEFAULT_TOLERANCE] sinon — et pour un
     * concept inconnu, dont la hauteur n'existe pas mais dont la fusion a quand même
     * besoin d'un poids.
     */
    fun toleranceFraction(label: String): Float {
        val key = canonical(label)
        if (key.isEmpty()) return DEFAULT_TOLERANCE
        TOLERANCE[key]?.let { return it }
        if (key.endsWith("s") && key.length > 3) TOLERANCE[key.dropLast(1)]?.let { return it }
        if (key.contains(' ')) FIRST_WORD_TOLERANCE[key.substringBefore(' ')]?.let { return it }
        return DEFAULT_TOLERANCE
    }

    /**
     * Poids de la branche sténopé dans [DistanceFusion] : `1 / (1 + tolérance)` —
     * une porte (3 %) pèse plus qu'un arbre (30 %), sans jamais tomber à zéro.
     */
    fun fusionWeight(label: String): Float = 1f / (1f + toleranceFraction(label))

    /** Forme de recherche d'une étiquette : « Personnes » → « personnes ». */
    fun canonical(label: String): String {
        val ascii = Normalizer.normalize(label, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()
        return ascii.replace(NON_WORD, " ").trim()
    }

    private val MARKS = "\\p{M}+".toRegex()
    private val NON_WORD = "[^a-z0-9]+".toRegex()

    /** Concepts connus → hauteur typique (m). Clés déjà « canoniques ». */
    val TABLE: Map<String, Float> = mapOf(
        // Personnes et animaux
        "person" to 1.70f, "personne" to 1.70f, "humain" to 1.70f, "human" to 1.70f,
        "homme" to 1.75f, "man" to 1.75f, "femme" to 1.68f, "woman" to 1.68f,
        "enfant" to 1.20f, "child" to 1.20f, "kid" to 1.20f,
        // Postures : une personne assise (1,25 m, dossier inclus) mesurée comme
        // debout (1,70 m) sort 36 % trop loin — les concepts chassés/suivis qui
        // portent la posture (champ « Follow », chasse iSee) ont leur entrée.
        "personne assise" to 1.25f, "seated person" to 1.25f, "sitting person" to 1.25f,
        "homme assis" to 1.28f, "seated man" to 1.28f, "femme assise" to 1.23f, "seated woman" to 1.23f,
        "cycliste" to 1.75f, "cyclist" to 1.75f,
        "fauteuil roulant" to 1.25f, "wheelchair" to 1.25f,
        "chien" to 0.55f, "dog" to 0.55f, "chat" to 0.25f, "cat" to 0.25f,
        "cheval" to 1.60f, "horse" to 1.60f,
        "animal" to 0.60f, "animaux" to 0.60f,
        // Véhicules
        "voiture" to 1.45f, "car" to 1.45f, "camion" to 2.80f, "truck" to 2.80f,
        "bus" to 3.00f, "moto" to 1.40f, "motorcycle" to 1.40f,
        "suv" to 1.65f, "van" to 1.95f, "camionnette" to 1.95f, "pickup" to 1.75f, "pick up" to 1.75f,
        "velo" to 1.10f, "bicycle" to 1.10f, "bike" to 1.10f, "trottinette" to 1.00f,
        "trottinette electrique" to 1.00f, "scooter" to 1.00f, "electric scooter" to 1.00f,
        // Mobilier
        "chaise" to 0.90f, "chair" to 0.90f, "table" to 0.75f,
        "canape" to 0.80f, "sofa" to 0.80f, "couch" to 0.80f, "lit" to 0.60f, "bed" to 0.60f,
        "bureau" to 0.75f, "desk" to 0.75f, "armoire" to 1.90f, "cupboard" to 1.90f,
        // Architecture et rue
        "porte" to 2.05f, "door" to 2.05f, "fenetre" to 1.30f, "window" to 1.30f,
        "mur" to 2.50f, "wall" to 2.50f, "panneau" to 2.20f, "sign" to 2.20f,
        "lampadaire" to 5.00f, "poubelle" to 0.90f, "bin" to 0.90f,
        "trash can" to 0.90f, "benne" to 1.50f, "dumpster" to 1.50f,
        // Barrière de chantier type Vauban ≈ 1 m (glissière 0,75 m : ordre de grandeur).
        "barriere" to 1.00f, "barrier" to 1.00f,
        // Extérieur — APPROXIMATIF, modèles très variables : borne 0,80 m
        // (plots et potelets urbains 0,6-1,0 m), clôture 1,50 m (clôtures et
        // grillages 1,0-2,0 m), portail 1,75 m (portails courants 1,5-2,0 m).
        "bollard" to 0.80f, "borne" to 0.80f, "plot" to 0.80f, "potelet" to 0.80f,
        "fence" to 1.50f, "cloture" to 1.50f, "grillage" to 1.50f,
        "gate" to 1.75f, "portail" to 1.75f,
        // Objets portables et de bureau
        "bouteille" to 0.25f, "bottle" to 0.25f, "verre" to 0.12f, "glass" to 0.12f,
        "cup" to 0.10f, "gobelet" to 0.12f, "mug" to 0.10f, "bol" to 0.09f,
        "telephone" to 0.15f, "phone" to 0.15f, "laptop" to 0.24f, "ordinateur" to 0.24f,
        "clavier" to 0.04f, "keyboard" to 0.04f, "ecran" to 0.40f, "screen" to 0.40f,
        "livre" to 0.24f, "book" to 0.24f, "cahier" to 0.21f, "notebook" to 0.21f,
        // Sacs et vêtements
        "sac" to 0.40f, "bag" to 0.40f, "backpack" to 0.45f, "sacados" to 0.45f,
        "valise" to 0.70f, "suitcase" to 0.70f, "casque" to 0.25f, "helmet" to 0.25f,
        // Végétation
        "arbre" to 4.00f, "tree" to 4.00f, "plante" to 0.50f, "plant" to 0.50f,
    )

    /** Incertitude relative quand le concept n'a pas de tolérance propre. */
    const val DEFAULT_TOLERANCE = 0.15f

    /**
     * Concepts → incertitude relative de leur hauteur typique. Seuls les concepts
     * qui s'écartent de [DEFAULT_TOLERANCE] y figurent : une porte normalisée (3 %),
     * un adulte (5-6 %), un enfant ou un animal (15-30 %). Ordres de grandeur
     * assumés, à resserrer par la recette au mètre ruban.
     */
    val TOLERANCE: Map<String, Float> = mapOf(
        "person" to 0.06f, "personne" to 0.06f, "humain" to 0.06f, "human" to 0.06f,
        "homme" to 0.05f, "man" to 0.05f, "femme" to 0.05f, "woman" to 0.05f,
        "personne assise" to 0.08f, "seated person" to 0.08f, "sitting person" to 0.08f,
        "homme assis" to 0.08f, "seated man" to 0.08f, "femme assise" to 0.08f, "seated woman" to 0.08f,
        "cycliste" to 0.10f, "cyclist" to 0.10f,
        "fauteuil roulant" to 0.12f, "wheelchair" to 0.12f,
        "chien" to 0.20f, "dog" to 0.20f, "chat" to 0.20f, "cat" to 0.20f,
        "cheval" to 0.10f, "horse" to 0.10f, "animal" to 0.30f, "animaux" to 0.30f,
        "voiture" to 0.08f, "car" to 0.08f, "camion" to 0.10f, "truck" to 0.10f,
        "bus" to 0.08f, "moto" to 0.12f, "motorcycle" to 0.12f,
        "suv" to 0.08f, "van" to 0.10f, "camionnette" to 0.10f, "pickup" to 0.10f, "pick up" to 0.10f,
        "velo" to 0.10f, "bicycle" to 0.10f, "bike" to 0.10f,
        "chaise" to 0.10f, "chair" to 0.10f, "table" to 0.10f, "lit" to 0.15f, "bed" to 0.15f,
        "porte" to 0.03f, "door" to 0.03f, "mur" to 0.20f, "wall" to 0.20f,
        "panneau" to 0.25f, "sign" to 0.25f, "arbre" to 0.30f, "tree" to 0.30f,
        "borne" to 0.20f, "bollard" to 0.20f, "plot" to 0.20f, "potelet" to 0.20f,
        "cloture" to 0.25f, "fence" to 0.25f, "grillage" to 0.25f,
        "portail" to 0.15f, "gate" to 0.15f, "barriere" to 0.15f, "barrier" to 0.15f,
        "bouteille" to 0.20f, "bottle" to 0.20f, "verre" to 0.20f, "glass" to 0.20f,
        "sac" to 0.25f, "bag" to 0.25f, "backpack" to 0.20f, "sacados" to 0.20f,
        "valise" to 0.15f, "suitcase" to 0.15f,
    )

    /**
     * Défauts du direct volontairement sans hauteur (voir le KDoc de classe) :
     * leur distance vient de la profondeur ou du plan de sol. Clés canoniques
     * anglaises (voir [canonical]).
     */
    val DEPTH_ONLY: Set<String> = setOf(
        "stairs", "sidewalk", "crosswalk", "pole", "traffic light", "curb",
        "kerb", "step", "steps", "bordure", "rebord", "curb ramp", "bateau",
        "ramp", "rampe", "gutter", "caniveau",
        // Feu piéton (31/10, ordre user : piétons seuls) : ni le caisson ni
        // la tête n'ont de hauteur typique projetable — profondeur ou plan
        // de sol. `traffic light` reste (saisie manuelle d'un feu voiture :
        // jamais de sténopé dessus non plus).
        "pedestrian traffic light", "pedestrian signal head",
    )

    /** Repli par premier mot connu : « sac à dos » trouve « sac ». */
    private val FIRST_WORD: Map<String, Float> = TABLE

    /** Même repli pour les tolérances : « clôture grillagée » trouve « clôture ». */
    private val FIRST_WORD_TOLERANCE: Map<String, Float> = TOLERANCE
}