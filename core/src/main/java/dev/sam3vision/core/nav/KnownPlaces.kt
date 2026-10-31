package dev.sam3vision.core.nav

import dev.sam3vision.core.distance.KnownSizes

/**
 * Lieux connus : nom usuel dicté → adresse postale exacte.
 *
 * Certains noms portent mal au géocodage : « Sacré-Cœur » existe aussi hors
 * de Paris (le repli BAN rend l'homonyme du Pas-de-Calais, que même le tri
 * par proximité peut garder quand la position manque). La table ci-dessous
 * court-circuite le doute : le nom usuel part vers l'adresse du lieu visé,
 * avant tout fournisseur.
 *
 * Comparaison insensible à la casse, aux accents, aux ligatures (« œ » vaut
 * « oe ») et à la ponctuation ([KnownSizes.canonical]), article initial
 * ignoré (« the », « le », « la », « les », « l' ») : « the Sacré Coeur »,
 * « le Sacré-Cœur » et « SACRE COEUR » désignent le même parvis. Seule
 * l'égalité stricte résout : « Montmartre » seul ou « restaurant près du
 * Sacré-Cœur » ne sont pas des noms du lieu et passent inchangés.
 *
 * Pur (JVM seule, sans Android ni coroutine) et ne lève jamais : entrée
 * blanche ou inconnue → rendue telle quelle.
 */
object KnownPlaces {

    /** Parvis de la basilique du Sacré-Cœur (Montmartre, Paris 18e). */
    const val SACRE_COEUR_ADDRESS = "35 Rue du Chevalier de la Barre"

    /** Article en tête de dictée : hors du nom du lieu. */
    private val LEADING_ARTICLE = Regex("^(?:the|le|la|les|l)\\s+")

    /**
     * Alias → adresse. Clés déjà canoniques ([KnownSizes.canonical]) :
     * minuscules, sans accents, ponctuation repliée en espaces.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "sacre coeur" to SACRE_COEUR_ADDRESS,
        "basilique sacre coeur" to SACRE_COEUR_ADDRESS,
        "basilique du sacre coeur" to SACRE_COEUR_ADDRESS,
        "sacre coeur montmartre" to SACRE_COEUR_ADDRESS,
        "sacre coeur basilica" to SACRE_COEUR_ADDRESS,
        "basilica of the sacred heart" to SACRE_COEUR_ADDRESS,
    )

    /**
     * Adresse du lieu nommé par [cleaned] (déjà normalisé par
     * [DestinationQuery.normalize]), ou [cleaned] inchangé si ce n'est pas
     * un lieu connu. Ne lève jamais.
     */
    fun resolve(cleaned: String): String {
        if (cleaned.isBlank()) return cleaned
        return runCatching {
            val key = LEADING_ARTICLE.replace(KnownSizes.canonical(expandLigatures(cleaned)), "")
            ALIASES[key] ?: cleaned
        }.getOrDefault(cleaned)
    }

    /**
     * Ligatures françaises (« Sacré-Cœur ») : NFD ne les décompose pas et le
     * canon les mangerait (« sacre c ur ») — « œ » vaut « oe », « æ » « ae ».
     */
    private fun expandLigatures(text: String): String =
        text.replace("œ", "oe").replace("Œ", "OE").replace("æ", "ae").replace("Æ", "AE")
}
