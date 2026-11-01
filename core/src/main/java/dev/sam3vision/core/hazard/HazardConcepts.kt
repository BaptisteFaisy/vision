package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PersonLabels

/**
 * Concepts qui font parler le guetteur danger : la personne proche
 * (ordre user du 01/11 : la voix danger ne parle plus des poteaux, et la
 * personne dit le seul mot d'évitement « Droite. »/« Gauche. ») et le
 * mobilier qui barre le passage (dire « Attention … » —
 * ordre user du 29/10 : iSee décrit, en douceur — les mots du guidage
 * disent où aller, l'attention dit quoi éviter).
 *
 * `barrier` n'est plus dans les défauts du direct (31/10 : son slot passe
 * à « keys », qui l'a rendu le 01/11 à la foule) ; il reste reconnu côté
 * client ([isHazard])
 * quand le backend le segmente (preset extérieur, champ « Follow »). Le
 * français reste reconnu : un label « barrière » compte comme « barrier ».
 * Lampadaires (« street lamp », « lampadaire », « réverbère »), panneaux
 * (« sign », « panneau », « traffic sign ») et potelets (« potelet »,
 * « plot » → « bollard ») s'y ajoutent (reel audit 31/10).
 *
 * Le poteau reste reconnu ([isPole]) mais ne parle plus : [isSpoken] le
 * tait, la personne parle à sa place. La reconnaissance est conservée
 * pour le repli haut-de-poteau de la Traversée
 * (`CrossingPrompts.selectPoleTopBox`, qui vise le haut du poteau pour y
 * lire le feu) — voix muette mais reconnaissance conservée.
 *
 * Liste fermée : un concept = un nom FR/EN ([HazardName]) ; la personne y
 * entre par [PersonLabels], sans table à part. Le « etc. » de l'ordre
 * s'ajoute ici (clé canonique → nom), jamais en dur dans une phrase.
 *
 * Foule (audit du 01/11) : la poussette, le fauteuil roulant, la trottinette,
 * le chien et l'enfant ont leur nom parlé — ils barrent le passage même sans
 * être un piéton. La poussette, le fauteuil, le chien et l'enfant se
 * contournent comme un corps ([HazardKind.PERSON]) ; la trottinette reste
 * diverse ([HazardKind.OTHER], c'est un véhicule, pas quelqu'un à éviter).
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
        "bollard" to HazardName("à la borne", "bollard"),
        "borne" to HazardName("à la borne", "bollard"),
        "fence" to HazardName("à la clôture", "fence"),
        "cloture" to HazardName("à la clôture", "fence"),
        "gate" to HazardName("au portail", "gate"),
        "portail" to HazardName("au portail", "gate"),
        "street lamp" to HazardName("au lampadaire", "street lamp"),
        "lampadaire" to HazardName("au lampadaire", "street lamp"),
        "reverbere" to HazardName("au lampadaire", "street lamp"),
        "sign" to HazardName("au panneau", "sign"),
        "panneau" to HazardName("au panneau", "sign"),
        "panneaux" to HazardName("au panneau", "sign"),
        "traffic sign" to HazardName("au panneau de signalisation", "traffic sign"),
        "potelet" to HazardName("au potelet", "bollard"),
        "plot" to HazardName("au plot", "bollard"),
        // Foule (01/11) : ce qui barre le passage sans être un piéton debout,
        // plus l'enfant et le chien. Pluriels irréguliers en dur (« children »,
        // « fauteuils roulants »), les autres tombent sur la coupe du « s ».
        "stroller" to HazardName("à la poussette", "stroller"),
        "poussette" to HazardName("à la poussette", "stroller"),
        "pram" to HazardName("à la poussette", "stroller"),
        "buggy" to HazardName("à la poussette", "stroller"),
        "wheelchair" to HazardName("au fauteuil roulant", "wheelchair"),
        "fauteuil roulant" to HazardName("au fauteuil roulant", "wheelchair"),
        "fauteuils roulants" to HazardName("au fauteuil roulant", "wheelchair"),
        "electric scooter" to HazardName("à la trottinette", "scooter"),
        "scooter" to HazardName("à la trottinette", "scooter"),
        "trottinette" to HazardName("à la trottinette", "scooter"),
        "dog" to HazardName("au chien", "dog"),
        "chien" to HazardName("au chien", "dog"),
        "child" to HazardName("à l'enfant", "child"),
        "children" to HazardName("à l'enfant", "child"),
        "enfant" to HazardName("à l'enfant", "child"),
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
     * (repli haut-de-poteau) : voix muette mais reconnaissance conservée.
     */
    fun isPole(label: String): Boolean = nameOf(label)?.en == "pole"

    /**
     * Vrai si [label] désigne une personne (le nom anglais fait foi, jamais
     * une table à part) : l'enfant en est une (il s'évite et se parle comme un
     * adulte), la poussette, le fauteuil, le chien et la trottinette non —
     * leur [kindOf] dira seulement qu'ils se contournent.
     */
    fun isPerson(label: String): Boolean {
        val en = nameOf(label)?.en ?: return false
        return en == "person" || en == "child"
    }

    /**
     * Vrai si [label] fait parler le guetteur : personne ou mobilier
     * suivi, sauf le poteau (muet depuis l'ordre user du 01/11).
     */
    fun isSpoken(label: String): Boolean = isHazard(label) && !isPole(label)

    /** Vrai si [label] désigne une barrière (P4, après la personne P3). */
    fun isBarrier(label: String): Boolean = nameOf(label)?.en == "barrier"

    /**
     * Sorte parlée de [label] : personne (tout ce qui se contourne comme un
     * corps — personne, enfant, poussette, fauteuil roulant, chien),
     * barrière, ou divers (poteau muet, borne, clôture, portail, trottinette).
     */
    fun kindOf(label: String): HazardKind {
        val en = nameOf(label)?.en ?: return HazardKind.OTHER
        return when (en) {
            "person", "child", "stroller", "wheelchair", "dog" -> HazardKind.PERSON
            "barrier" -> HazardKind.BARRIER
            else -> HazardKind.OTHER
        }
    }
}

/**
 * Sorte parlée, dans l'ordre de parole (personne P3 avant barrière P4,
 * divers avec le reste) : l'arbitre voix y lit le rang du guetteur danger.
 */
enum class HazardKind {
    /** Corps qui bloque et s'évite (personne, enfant, poussette, fauteuil roulant, chien) : P3. */
    PERSON,

    /** Barrière : descriptive, après la personne (P4). */
    BARRIER,

    /** Divers : poteau muet, borne, clôture, portail… (avec le reste). */
    OTHER,
}
