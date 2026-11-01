package dev.sam3vision.core.live

/**
 * Prompts du direct : concepts suivis par SAM 3.1, liste courte modifiable depuis l'UI.
 * [normalize] assainit avant l'envoi au backend (`max_objects` borne la réponse).
 *
 * Spec v4 : listes verrouillées, deux mondes.
 * - Dehors ([default]) : 16 concepts — personne, voiture, feu piéton, bordure,
 *   trottoir, passage piéton, poteau, escaliers, grillage, mur, barrière, panneau,
 *   lampadaire, animal, poubelle, souvenirs.
 * - Dedans ([indoor]) : 10 objets — clés, boîte de médicaments, lunettes, boîtier
 *   de lunettes, téléphone, chaussures, porte, murs, humain, chaussettes.
 *
 * Supprimés de la détection (spec v4) : bus, vélo, camion, moto, clés dehors,
 * borne, arbre, portail, bateau — plus les anciens doublons (tête du feu,
 * trottinette, poussette, enfant, chien, bonnet).
 *
 * Concepts en anglais : c'est la langue que SAM 3.1 segmente le mieux.
 * [forMode] bascule les listes dehors/dedans (`Settings.outdoorMode` côté sonde).
 * « Find my X » ([withConcept]) ajoute le concept, le dernier cède son slot au
 * plafond. Champ « Follow » ([field]) gardé.
 */
object LivePrompts {

    /**
     * Concepts dehors : les **16** de la spec v4, dans l'ordre de la spec.
     * Liste unique normal/Traversée : la bascule ne change plus les prompts.
     */
    val default: List<String> = listOf(
        "person", "car", "pedestrian traffic light", "curb", "sidewalk", "crosswalk",
        "pole", "stairs", "fence", "wall", "barrier", "sign", "street lamp",
        "animal", "trash can", "souvenir shop",
    )

    /**
     * Preset extérieur : replié sur [default] (spec v4, détection unifiée à 16
     * concepts). Gardé pour la pastille « Outdoor » du direct, qui envoie la
     * même liste que « Street ».
     */
    val outdoor: List<String> = default

    /**
     * Concepts dedans : les **10** objets de la spec v4, dans l'ordre de la spec.
     */
    val indoor: List<String> = listOf(
        "keys", "medicine box", "glasses", "glasses case", "phone", "shoes",
        "door", "wall", "person", "socks",
    )

    /**
     * Liste du monde sensoriel : dehors = [default] (16 concepts), dedans =
     * [indoor] (10 objets). La sonde iSee bascule via `Settings.outdoorMode`.
     */
    fun forMode(outdoor: Boolean): List<String> = if (outdoor) default else indoor

    /**
     * Concepts suivis explicitement : [effective] moins la liste du monde
     * (comparaison insensible à la casse) — les pastilles des suivis seules
     * s'affichent, vide = tout s'affiche. Pur, testé en JVM.
     */
    fun followedOf(effective: List<String>, outdoorMode: Boolean): List<String> {
        val base = forMode(outdoorMode).map { it.trim().lowercase() }.toSet()
        return normalize(effective).filter { it.trim().lowercase() !in base }
    }

    /**
     * Nombre maximal de concepts : **22** (le backend suit : `MAX_PROMPTS = 22`
     * sur `/live`, `PROMPT_LIMIT = 22` côté tracker ; un serveur resté en
     * arrière **refuse** la frame, il ne tronque pas). Les listes spec v4
     * (16 dehors, 10 dedans) tiennent sous ce plafond ; la marge sert à
     * « Find my X » ([withConcept]), pas à allonger le suivi au repos.
     */
    const val MAX = 22

    /** Longueur maximale d'un prompt : au-delà, ce n'est plus un concept. */
    const val MAX_LENGTH = 32

    /**
     * Nettoie une liste de prompts : espaces retirés, vides et doublons éliminés,
     * prompts trop longs rejetés, liste bornée à [MAX]. Une liste qui devient vide
     * retombe sur [default].
     */
    fun normalize(prompts: List<String>): List<String> {
        val cleaned = prompts.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= MAX_LENGTH }
            .distinct()
            .take(MAX)
            .toList()
        return cleaned.ifEmpty { default }
    }

    /**
     * Ordre « Find my X » : [label] rejoint [prompts], même au plafond —
     * le dernier concept cède alors son slot à l'ordre explicite (les
     * concepts de tête ne bougent jamais). Déjà présent (comparaison
     * insensible à la casse), vide ou trop long : liste inchangée. Pur, testé
     * en JVM.
     */
    fun withConcept(prompts: List<String>, label: String): List<String> {
        val clean = label.trim()
        if (clean.isEmpty() || clean.length > MAX_LENGTH) return prompts
        if (prompts.any { it.trim().equals(clean, ignoreCase = true) }) return prompts
        val base = if (prompts.size >= MAX) prompts.dropLast(1) else prompts
        return normalize(base + clean)
    }

    /**
     * Champ « Follow » saisi par l'utilisateur : découpé sur les virgules,
     * points-virgules et retours ligne, nettoyé des vides — vide = [default].
     * La session borne et normalise ensuite (voir [normalize]).
     */
    fun field(text: String): List<String> =
        text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { default }
}
