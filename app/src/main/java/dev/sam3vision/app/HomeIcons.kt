package dev.sam3vision.app

import dev.sam3vision.core.live.HomeModes

/**
 * Icônes du mode Maison : une par objet (`drawable-nodpi`, pictos blancs sur fond
 * bleu nuit, générés pour rester lisibles à petite taille). La rue n'a pas d'icône :
 * ses pastilles restent du texte seul, comme avant.
 */
object HomeIcons {
    private val BY_LABEL: Map<String, Int> = mapOf(
        "keys" to R.drawable.ic_home_keys,
        "phone" to R.drawable.ic_home_phone,
        "wallet" to R.drawable.ic_home_wallet,
        "glasses" to R.drawable.ic_home_glasses,
        "tv remote" to R.drawable.ic_home_remote,
        "charger" to R.drawable.ic_home_charger,
        "mug" to R.drawable.ic_home_mug,
        "medicine box" to R.drawable.ic_home_medbox,
        "pill bottle" to R.drawable.ic_home_pills,
        "blister pack" to R.drawable.ic_home_blister,
        "thermometer" to R.drawable.ic_home_thermo,
        "glasses case" to R.drawable.ic_home_glasscase,
        "tissue box" to R.drawable.ic_home_tissues,
        "bandage" to R.drawable.ic_home_bandage,
        "drinking glass" to R.drawable.ic_home_glass,
        "bottle" to R.drawable.ic_home_bottle,
        "scissors" to R.drawable.ic_home_scissors,
        "pen" to R.drawable.ic_home_pen,
        "book" to R.drawable.ic_home_book,
        "newspaper" to R.drawable.ic_home_news,
        "light switch" to R.drawable.ic_home_switch,
    )

    /** Icône d'un label SAM, `null` si ce n'est pas un objet maison. */
    fun iconFor(label: String): Int? = BY_LABEL[label.trim().lowercase()]

    /** Icône d'un preset (son premier objet), `null` pour la rue et l'inconnu. */
    fun presetIcon(id: String?): Int? = when (id) {
        HomeModes.ESSENTIELS.id -> R.drawable.ic_home_keys
        HomeModes.SANTE.id -> R.drawable.ic_home_medbox
        HomeModes.CUISINE.id -> R.drawable.ic_home_glass
        else -> null
    }
}
