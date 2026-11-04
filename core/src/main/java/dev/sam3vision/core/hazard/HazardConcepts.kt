package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PersonLabels

/**
 * Concepts qui font parler le guetteur danger : tous disent le mot
 * d'évitement « Droite. »/« Gauche. » en continu (personne niveau 3,
 * grillages/murs/barrières niveau 5, poteaux/mobilier/divers niveau 8 —
 * ordre user du 03/11 : les obstacles s'esquivent, ils ne se nomment
 * plus ; la position optimale se dit en permanence).
 *
 * Le français reste reconnu : un label « barrière » compte comme
 * « barrier ». Le poteau parle depuis la v4 (niveau 8) : [isSpoken] ne le
 * tait plus — [isPole] reste, lu par le repli haut-de-poteau de la
 * Traversée (`CrossingPrompts.selectPoleTopBox`, qui vise le haut du
 * poteau pour y lire le feu). Bornes, portails et trottinettes sortis
 * (spec v4 : plus détectés).
 *
 * Retirés v4 (ordres user) : la borne (« bollard », « borne », « plot »,
 * « potelet »), le portail (« gate », « portail ») et la trottinette
 * (« scooter », « trottinette ») — reconnus nulle part, ils ne parlent
 * plus. Le reste de la foule (poussette, fauteuil roulant, chien,
 * enfant), le chat, la benne, le bonnet et les chaussettes restent
 * reconnus (D-13 : jamais supprimés par l'user).
 *
 * Liste fermée : un concept = un nom FR/EN ([HazardName]) ; la personne y
 * entre par [PersonLabels], sans table à part. Le « etc. » de l'ordre
 * s'ajoute ici (clé canonique → nom), jamais en dur dans une phrase.
 */
object HazardConcepts {

    /**
     * Nom parlé du concept : [fr] le complément (« à la barrière »,
     * « à la personne » — contraction incluse), [en] le nom nu
     * (« barrier »).
     */
    data class HazardName(val fr: String, val en: String)

    private val NAMES: Map<String, HazardName> = mapOf(
        "barrier" to HazardName("à la barrière", "barrier"),
        "barriere" to HazardName("à la barrière", "barrier"),
        "pole" to HazardName("au poteau", "pole"),
        "poteau" to HazardName("au poteau", "pole"),
        "poteaux" to HazardName("au poteau", "pole"),
        "fence" to HazardName("à la clôture", "fence"),
        "cloture" to HazardName("à la clôture", "fence"),
        "grillage" to HazardName("à la clôture", "fence"),
        "wall" to HazardName("au mur", "wall"),
        "walls" to HazardName("au mur", "wall"),
        "mur" to HazardName("au mur", "wall"),
        "murs" to HazardName("au mur", "wall"),
        "street lamp" to HazardName("au lampadaire", "street lamp"),
        "lampadaire" to HazardName("au lampadaire", "street lamp"),
        "reverbere" to HazardName("au lampadaire", "street lamp"),
        "sign" to HazardName("au panneau", "sign"),
        "panneau" to HazardName("au panneau", "sign"),
        "panneaux" to HazardName("au panneau", "sign"),
        "traffic sign" to HazardName("au panneau de signalisation", "traffic sign"),
        // Foule (D-13) : ce qui barre le passage sans être un piéton debout,
        // plus l'enfant et le chien — noms parlés propres, sorte PERSON.
        "stroller" to HazardName("à la poussette", "stroller"),
        "poussette" to HazardName("à la poussette", "stroller"),
        "pram" to HazardName("à la poussette", "stroller"),
        "buggy" to HazardName("à la poussette", "stroller"),
        "wheelchair" to HazardName("au fauteuil roulant", "wheelchair"),
        "fauteuil roulant" to HazardName("au fauteuil roulant", "wheelchair"),
        "fauteuils roulants" to HazardName("au fauteuil roulant", "wheelchair"),
        "dog" to HazardName("au chien", "dog"),
        "chien" to HazardName("au chien", "dog"),
        "child" to HazardName("à l'enfant", "child"),
        "children" to HazardName("à l'enfant", "child"),
        "enfant" to HazardName("à l'enfant", "child"),
        // Divers v4 (niveau 8) : l'animal générique et le chat, la poubelle,
        // la benne, les souvenirs, le bonnet et les chaussettes.
        "animal" to HazardName("à l'animal", "animal"),
        "animaux" to HazardName("à l'animal", "animal"),
        "cat" to HazardName("au chat", "cat"),
        "chat" to HazardName("au chat", "cat"),
        "trash can" to HazardName("à la poubelle", "trash can"),
        "poubelle" to HazardName("à la poubelle", "trash can"),
        "bin" to HazardName("à la poubelle", "trash can"),
        "benne" to HazardName("à la benne", "dumpster"),
        "dumpster" to HazardName("à la benne", "dumpster"),
        "souvenir shop" to HazardName("à la boutique de souvenirs", "souvenir shop"),
        "beanie" to HazardName("au bonnet", "beanie"),
        "bonnet" to HazardName("au bonnet", "beanie"),
        "socks" to HazardName("aux chaussettes", "socks"),
        "chaussettes" to HazardName("aux chaussettes", "socks"),
    )

    /**
     * Nom parlé de [label], `null` si ni personne ni mobilier suivi.
     * Libellé entier d'abord (dont « street lamp », « traffic sign »),
     * puis mot à mot ([wordHit]) : le serveur échoie le prompt tel que
     * saisi, les composés viennent des suivis (« metal pole » en Follow).
     */
    fun nameOf(label: String): HazardName? {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return null
        NAMES[key]?.let { return it }
        if (key.endsWith("s") && key.length > 3) NAMES[key.dropLast(1)]?.let { return it }
        wordHit(key)?.let { return it }
        if (PersonLabels.isPerson(label)) return HazardName("à la personne", "person")
        return null
    }

    /**
     * Premier mot connu NON-personne de [NAMES] (pluriel toléré), `null`
     * sinon. Les entrées personne sont exclues : « child seat » (siège
     * auto) ne doit pas annoncer un enfant de niveau 3 — une personne ne
     * se reconnaît qu'en libellé entier. Même raison pour [PersonLabels],
     * jamais consulté mot à mot (« pedestrian traffic light » contient
     * « pedestrian » et n'est pas une personne). Premier mot assumé :
     * « sign language » donne « sign » (niveau 8, les composés ne venant
     * que des suivis) quand « poteau métallique » donne « poteau ».
     */
    private fun wordHit(key: String): HazardName? {
        for (word in key.split(' ')) {
            if (word.isEmpty()) continue
            nonPerson(NAMES[word])?.let { return it }
            if (word.endsWith("s") && word.length > 3) nonPerson(NAMES[word.dropLast(1)])?.let { return it }
        }
        return null
    }

    /** [name] sauf s'il parle d'une personne (`en == "child"`, voir [isPerson]). */
    private fun nonPerson(name: HazardName?): HazardName? =
        if (name?.en == "child") null else name

    /** Vrai si [label] désigne une personne ou du mobilier suivi (casse, accents, pluriel neutralisés). */
    fun isHazard(label: String): Boolean = nameOf(label) != null

    /**
     * Vrai si [label] désigne un poteau (le nom anglais fait foi, jamais
     * une table à part). Requis par `CrossingPrompts.selectPoleTopBox`
     * (repli haut-de-poteau) : voix parlante depuis la v4 (niveau 8).
     */
    fun isPole(label: String): Boolean = nameOf(label)?.en == "pole"

    /**
     * Vrai si [label] désigne une personne (le nom anglais fait foi, jamais
     * une table à part) : l'enfant en est une (il s'évite et se parle comme
     * un adulte), la poussette, le fauteuil et le chien non — voir
     * [isGuidedBody] pour le guidage, qui les traite pareil.
     */
    fun isPerson(label: String): Boolean {
        val en = nameOf(label)?.en ?: return false
        return en == "person" || en == "child"
    }

    /**
     * Vrai si [label] se guide comme une personne (ordre user du 02/11) :
     * personne, enfant, poussette, fauteuil roulant, chien ([kindOf] vaut
     * alors [HazardKind.PERSON]) — mot d'évitement continu, portée et
     * priorité personne. Le poteau n'y est pas (continu à part, niveau 8).
     */
    fun isGuidedBody(label: String): Boolean = kindOf(label) == HazardKind.PERSON

    /**
     * Vrai si [label] fait parler le guetteur : personne ou mobilier
     * suivi — le poteau parle depuis la v4 (niveau 8), la borne et le
     * portail ne sont plus reconnus ([isHazard] les ignore).
     */
    fun isSpoken(label: String): Boolean = isHazard(label)

    /** Vrai si [label] désigne une barrière (niveau 5, avec grillages et murs). */
    fun isBarrier(label: String): Boolean = nameOf(label)?.en == "barrier"

    /**
     * Vrai si [label] désigne un mur (le nom anglais fait foi, jamais une
     * table à part) : jamais nommé à la voix (ordre user — les murs
     * s'esquivent, ils ne se disent pas), il parle en mot d'évitement
     * continu comme le poteau ([HazardWatcher]).
     */
    fun isWall(label: String): Boolean = nameOf(label)?.en == "wall"

    /**
     * Niveau VoiceBus v4 de [label] : 2 la personne, 4 les grillages, les
     * murs et les barrières, 7 les poteaux, le mobilier vertical et les
     * divers. Label inconnu = 7 (divers, jamais la priorité d'une personne).
     */
    fun levelOf(label: String): Int {
        val en = nameOf(label)?.en ?: return HazardWatcher.LEVEL_POLE
        return when {
            kindOf(label) == HazardKind.PERSON -> HazardWatcher.LEVEL_PERSON
            en == "fence" || en == "wall" || en == "barrier" -> HazardWatcher.LEVEL_FENCE
            else -> HazardWatcher.LEVEL_POLE
        }
    }

    /**
     * Sorte parlée de [label] : personne (tout ce qui se contourne comme un
     * corps — personne, enfant, poussette, fauteuil roulant, chien),
     * barrière, poteau, ou divers (clôture, mur, animal, poubelle…).
     * Toutes disent le mot d'évitement en continu, voir [HazardWatcher].
     */
    fun kindOf(label: String): HazardKind {
        val en = nameOf(label)?.en ?: return HazardKind.OTHER
        return when (en) {
            "person", "child", "stroller", "wheelchair", "dog" -> HazardKind.PERSON
            "barrier" -> HazardKind.BARRIER
            "pole" -> HazardKind.POLE
            else -> HazardKind.OTHER
        }
    }
}

/**
 * Sorte parlée : personne (niveau 3), barrière (niveau 5, avec les
 * grillages et les murs), poteau (niveau 8), divers (niveau 8 — le mur,
 * niveau 5, en fait partie). Toutes disent le mot d'évitement en continu.
 */
enum class HazardKind {
    /** Corps qui bloque et s'évite (personne, enfant, poussette, fauteuil roulant, chien : niveau 3). */
    PERSON,

    /** Barrière : mot d'évitement continu (niveau 5, avec grillages et murs). */
    BARRIER,

    /** Poteau : mot d'évitement continu (niveau 8). */
    POLE,

    /** Divers : clôture, mur, lampadaire, panneau, animal, poubelle, souvenirs… (mot continu). */
    OTHER,
}
