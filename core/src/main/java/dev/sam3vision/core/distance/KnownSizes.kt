package dev.sam3vision.core.distance

import java.text.Normalizer

/**
 * Hauteurs réelles typiques, en mètres, par concept.
 *
 * Le direct suit des concepts **saisis par l'utilisateur** : la table couvre les
 * concepts par défaut de `LivePrompts` et leurs synonymes français/anglais. Un
 * concept absent reste **inconnu** — mieux vaut ne rien afficher qu'un chiffre
 * faux. Les valeurs sont des ordres de grandeur (un humain adulte, une voiture
 * de tourisme), pas des mesures : l'estimation monocular reste à ±15-20 %.
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
        "chien" to 0.55f, "dog" to 0.55f, "chat" to 0.25f, "cat" to 0.25f,
        "cheval" to 1.60f, "horse" to 1.60f,
        // Véhicules
        "voiture" to 1.45f, "car" to 1.45f, "camion" to 2.80f, "truck" to 2.80f,
        "bus" to 3.00f, "moto" to 1.40f, "motorcycle" to 1.40f,
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

    /** Repli par premier mot connu : « sac à dos » trouve « sac ». */
    private val FIRST_WORD: Map<String, Float> = TABLE
}