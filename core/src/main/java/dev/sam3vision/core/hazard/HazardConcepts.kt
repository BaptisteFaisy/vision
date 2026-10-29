package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concepts du mobilier qui barre le passage : ce que SAM doit suivre pour
 * dire « Attention … » (ordre user du 29/10 : iSee décrit, en douceur — les
 * mots du guidage disent où aller, l'attention dit quoi éviter).
 *
 * `barrier` est déjà dans les défauts du direct (dangers extérieurs, ordre
 * user du 28/10) ; les autres formes sont reconnues côté client ([isHazard])
 * quand le backend les segmente. Le français reste reconnu : un label
 * « barrière » compte comme un « barrier ».
 *
 * Liste fermée : un concept = un nom FR/EN ([HazardName]). Le « etc. » de
 * l'ordre s'ajoute ici (clé canonique → nom), jamais en dur dans une phrase.
 */
object HazardConcepts {

    /**
     * Nom parlé du concept : [fr] le complément (« à la barrière »,
     * « au poteau » — contraction incluse), [en] le nom nu (« barrier »).
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
    )

    /** Nom parlé de [label], `null` si ce n'est pas du mobilier suivi. */
    fun nameOf(label: String): HazardName? {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return null
        NAMES[key]?.let { return it }
        if (key.endsWith("s") && key.length > 3) return NAMES[key.dropLast(1)]
        return null
    }

    /** Vrai si [label] désigne du mobilier suivi (casse, accents, pluriel neutralisés). */
    fun isHazard(label: String): Boolean = nameOf(label) != null
}
