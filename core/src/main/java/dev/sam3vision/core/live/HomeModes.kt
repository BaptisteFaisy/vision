package dev.sam3vision.core.live

/**
 * Mode Maison : trois presets d'objets d'appartement pour le direct, ceux qu'un
 * malvoyant peine à retrouver (médicaments, clés, lunettes…). Chaque preset tient
 * sous le plafond [LivePrompts.MAX] : le téléphone ne suit qu'un preset à la fois,
 * on bascule par les pastilles sans quitter le direct.
 *
 * Les concepts sont en anglais (comme [LivePrompts.default]) : c'est la langue que
 * SAM 3.1 segmente le mieux. [french] traduit les labels pour les pastilles.
 */
object HomeModes {

    /** Un preset : identifiant stable, nom français, concepts SAM. */
    data class Preset(val id: String, val labelFr: String, val prompts: List<String>)

    /** Objets qu'on égare partout dans l'appartement. */
    val ESSENTIELS = Preset(
        id = "essentiels",
        labelFr = "Essentiels",
        prompts = listOf("keys", "phone", "wallet", "glasses", "tv remote", "charger", "mug"),
    )

    /** Santé : la boîte de médicaments, le pilulier, le thermomètre… */
    val SANTE = Preset(
        id = "sante",
        labelFr = "Santé",
        prompts = listOf(
            "medicine box", "pill bottle", "blister pack", "thermometer",
            "glasses case", "tissue box", "bandage",
        ),
    )

    /** Cuisine et salon : le verre, la bouteille, les ciseaux… */
    val CUISINE = Preset(
        id = "cuisine",
        labelFr = "Cuisine & salon",
        prompts = listOf(
            "drinking glass", "bottle", "scissors", "pen", "book", "newspaper", "light switch",
        ),
    )

    /** Les trois presets, dans l'ordre des pastilles du direct. */
    val ALL: List<Preset> = listOf(ESSENTIELS, SANTE, CUISINE)

    /** Preset d'ouverture du bouton Maison : les essentiels. */
    val DEFAULT: Preset = ESSENTIELS

    /** Libellés français des 21 concepts maison (pastilles du bas du direct). */
    private val FRENCH = mapOf(
        "keys" to "clés",
        "phone" to "téléphone",
        "wallet" to "portefeuille",
        "glasses" to "lunettes",
        "tv remote" to "télécommande",
        "charger" to "chargeur",
        "mug" to "tasse",
        "medicine box" to "boîte de médicaments",
        "pill bottle" to "pilulier",
        "blister pack" to "plaquette",
        "thermometer" to "thermomètre",
        "glasses case" to "étui à lunettes",
        "tissue box" to "boîte de mouchoirs",
        "bandage" to "pansement",
        "drinking glass" to "verre",
        "bottle" to "bouteille",
        "scissors" to "ciseaux",
        "pen" to "stylo",
        "book" to "livre",
        "newspaper" to "journal",
        "light switch" to "interrupteur",
    )

    /** Preset par identifiant, `null` si inconnu. */
    fun byId(id: String?): Preset? = ALL.firstOrNull { it.id == id }

    /**
     * Identifiant du preset dont les concepts sont exactement [prompts]
     * (la liste d'état du direct, déjà normalisée), `null` sinon (rue, champ
     * « Suivre », traversée…).
     */
    fun activeId(prompts: List<String>): String? = ALL.firstOrNull { it.prompts == prompts }?.id

    /**
     * Libellé français d'un label SAM, ou le label brut s'il n'est pas un objet
     * maison : le mode rue garde ses étiquettes inchangées.
     */
    fun french(label: String): String = FRENCH[label.trim().lowercase()] ?: label
}
