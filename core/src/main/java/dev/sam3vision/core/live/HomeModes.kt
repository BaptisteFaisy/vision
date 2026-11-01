package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes

/**
 * Mode Maison : trois presets d'objets d'appartement pour le direct, ceux qu'un
 * malvoyant peine à retrouver (médicaments, clés, lunettes…). Chaque preset tient
 * sous le plafond [LivePrompts.MAX] : le téléphone ne suit qu'un preset à la fois,
 * on bascule par les pastilles sans quitter le direct.
 *
 * Les concepts sont en anglais (comme [LivePrompts.default]) : c'est la langue que
 * SAM 3.1 segmente le mieux. L'affichage garde l'anglais ; [FRENCH] ne sert
 * qu'à comprendre les mots français dictés.
 */
object HomeModes {

    /** Un preset : identifiant stable, nom anglais, concepts SAM. */
    data class Preset(val id: String, val label: String, val prompts: List<String>)

    /** Objets qu'on égare partout dans l'appartement. */
    val ESSENTIELS = Preset(
        id = "essentiels",
        label = "Essentials",
        prompts = listOf("keys", "phone", "wallet", "glasses", "tv remote", "charger", "mug"),
    )

    /** Santé : la boîte de médicaments, le pilulier, le thermomètre… */
    val SANTE = Preset(
        id = "sante",
        label = "Health",
        prompts = listOf(
            "medicine box", "pill bottle", "blister pack", "thermometer",
            "glasses case", "tissue box", "bandage",
        ),
    )

    /** Cuisine et salon : le verre, la bouteille, les ciseaux… */
    val CUISINE = Preset(
        id = "cuisine",
        label = "Kitchen & living room",
        prompts = listOf(
            "drinking glass", "bottle", "scissors", "pen", "book", "newspaper", "light switch",
        ),
    )

    /** Les trois presets, dans l'ordre des pastilles du direct. */
    val ALL: List<Preset> = listOf(ESSENTIELS, SANTE, CUISINE)

    /** Preset d'ouverture du bouton Maison : les essentiels. */
    val DEFAULT: Preset = ESSENTIELS

    /** Libellés français des 21 concepts maison (compréhension vocale). */
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
     * « Follow », traversée…).
     */
    fun activeId(prompts: List<String>): String? = ALL.firstOrNull { it.prompts == prompts }?.id

    /**
     * Libellé affiché d'un label SAM : l'anglais tel quel (les concepts maison
     * sont déjà anglais), rogné ; le mode rue garde ses étiquettes.
     */
    fun display(label: String): String = label.trim()

    /** Les 21 concepts maison, toutes pastilles : le vocabulaire chassable à la voix. */
    val CONCEPTS: Set<String> = ALL.flatMap { it.prompts }.toSet()

    /**
     * Mots parlés d'un seul mot vers les concepts à libellé français composé
     * (« médicaments » vise la boîte, « étui » l'étui à lunettes). Clés déjà
     * canoniques ([KnownSizes.canonical]).
     */
    private val WORD_ALIASES = mapOf(
        "clef" to "keys",
        "medicaments" to "medicine box",
        "mouchoirs" to "tissue box",
        "etui" to "glasses case",
    )

    /** Libellés français d'un seul mot → concept (« cles » → « keys »). */
    private val WORD_FR: Map<String, String> = buildMap {
        FRENCH.forEach { (concept, label) ->
            val key = KnownSizes.canonical(label)
            if (!key.contains(' ')) put(key, concept)
        }
    }

    /** Concepts anglais d'un seul mot → eux-mêmes (« keys » dicté en anglais). */
    private val WORD_EN: Map<String, String> = buildMap {
        ALL.flatMap { it.prompts }.forEach { concept ->
            val key = KnownSizes.canonical(concept)
            if (!key.contains(' ')) put(key, concept)
        }
    }

    /**
     * Mot dicté → concept SAM maison, `null` si inconnu. Tolérant : casse,
     * accents et ponctuation neutralisés, singulier/pluriel simple accepté
     * (« clé » → « keys »), anglais accepté, alias pour les libellés composés.
     * Un texte de plusieurs mots n'est jamais un mot : `null`.
     */
    fun conceptForWord(word: String): String? {
        val key = KnownSizes.canonical(word)
        if (key.isEmpty() || key.contains(' ')) return null
        WORD_FR[key]?.let { return it }
        WORD_EN[key]?.let { return it }
        WORD_ALIASES[key]?.let { return it }
        val flip = if (key.endsWith("s")) key.dropLast(1) else key + "s"
        WORD_FR[flip]?.let { return it }
        WORD_EN[flip]?.let { return it }
        return WORD_ALIASES[flip]
    }

    /**
     * Vrai quand le direct suit des concepts maison : un preset ([activeId]) ou
     * la chasse lancée par un mot parlé (un ou plusieurs concepts maison). La
     * rue, la traversée et le champ « Follow » libre n'y sont pas.
     */
    fun isHome(prompts: List<String>): Boolean {
        if (prompts.isEmpty()) return false
        if (activeId(prompts) != null) return true
        return prompts.all { CONCEPTS.contains(it.trim().lowercase()) }
    }

    /**
     * Texte parlé ou écrit → concept à chasser par SAM, `null` = question LLM.
     * Un seul mot du vocabulaire maison, mode maison actif et SAM allumé : le
     * LLM n'est pas appelé, SAM resegmente et la voix dit où c'est.
     */
    fun voiceConcept(text: String, prompts: List<String>, samEnabled: Boolean): String? {
        if (!samEnabled || !isHome(prompts)) return null
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
        if (words.size != 1) return null
        return conceptForWord(words[0])
    }
}
