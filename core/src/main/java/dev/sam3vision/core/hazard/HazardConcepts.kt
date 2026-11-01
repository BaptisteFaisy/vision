package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PersonLabels

/**
 * Concepts qui font parler le guetteur danger : la personne et le poteau
 * (mot d'évitement « Droite. »/« Gauche. » en continu — niveaux 2 et 7,
 * ordre user du 01/11 : la position optimale se dit en permanence), les
 * grillages, les murs et les barrières (niveau 4, moins de 6 m), le
 * mobilier vertical (lampadaire, panneau) et les divers (niveau 7, moins
 * de 6 m, spec v4). Dire « Attention … » — iSee décrit, en douceur ; les
 * mots du guidage disent où aller, l'attention dit quoi éviter.
 *
 * Le français reste reconnu : un label « barrière » compte comme
 * « barrier ». Le poteau parle depuis la v4 (niveau 7) : [isSpoken] ne le
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
        // Divers v4 (niveau 7) : l'animal générique et le chat, la poubelle,
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

    /** Nom parlé de [label], `null` si ni personne ni mobilier suivi. */
    fun nameOf(label: String): HazardName? {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return null
        NAMES[key]?.let { return it }
        if (key.endsWith("s") && key.length > 3) NAMES[key.dropLast(1)]?.let { return it }
        if (PersonLabels.isPerson(label)) return HazardName("à la personne", "person")
        return null
    }

    /** Vrai si [label] désigne une personne ou du mobilier suivi (casse, accents, pluriel neutralisés). */
    fun isHazard(label: String): Boolean = nameOf(label) != null

    /**
     * Vrai si [label] désigne un poteau (le nom anglais fait foi, jamais
     * une table à part). Requis par `CrossingPrompts.selectPoleTopBox`
     * (repli haut-de-poteau) : voix parlante depuis la v4 (niveau 7).
     */
    fun isPole(label: String): Boolean = nameOf(label)?.en == "pole"

    /**
     * Vrai si [label] désigne une personne (le nom anglais fait foi, jamais
     * une table à part) : l'enfant en est une (il s'évite et se parle comme
     * un adulte), la poussette, le fauteuil et le chien non — leur [kindOf]
     * dira seulement qu'ils se contournent.
     */
    fun isPerson(label: String): Boolean {
        val en = nameOf(label)?.en ?: return false
        return en == "person" || en == "child"
    }

    /**
     * Vrai si [label] fait parler le guetteur : personne ou mobilier
     * suivi — le poteau parle depuis la v4 (niveau 7), la borne et le
     * portail ne sont plus reconnus ([isHazard] les ignore).
     */
    fun isSpoken(label: String): Boolean = isHazard(label)

    /** Vrai si [label] désigne une barrière (niveau 4, avec grillages et murs). */
    fun isBarrier(label: String): Boolean = nameOf(label)?.en == "barrier"

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
     * Clé AntiRepeat v4 de [label] (annonce douce) : `human-soft` la
     * personne (le mot continu dit `human-imminent` / `pole-avoid`, voir
     * [HazardWatcher]), `fence` les grillages, murs et barrières, `pole`
     * le reste. Label inconnu = `pole`.
     */
    fun antiRepeatKey(label: String): String = when (levelOf(label)) {
        HazardWatcher.LEVEL_PERSON -> HazardWatcher.KEY_HUMAN_SOFT
        HazardWatcher.LEVEL_FENCE -> HazardWatcher.KEY_FENCE
        else -> HazardWatcher.KEY_POLE
    }

    /**
     * Sorte parlée de [label] : personne (tout ce qui se contourne comme un
     * corps — personne, enfant, poussette, fauteuil roulant, chien),
     * barrière, poteau (mot d'évitement continu, niveau 7), ou divers
     * (clôture, mur, animal, poubelle…).
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
 * Sorte parlée : personne (niveau 2, mot continu), barrière (niveau 4,
 * avec les grillages et les murs), poteau (niveau 7, mot continu),
 * divers (niveau 7, avec le reste).
 */
enum class HazardKind {
    /** Corps qui bloque et s'évite (personne, enfant, poussette, fauteuil roulant, chien : niveau 2). */
    PERSON,

    /** Barrière : descriptive, niveau 4 (avec grillages et murs). */
    BARRIER,

    /** Poteau : mot d'évitement continu comme la personne (niveau 7). */
    POLE,

    /** Divers : clôture, mur, lampadaire, panneau, animal, poubelle, souvenirs… (niveau 7). */
    OTHER,
}
