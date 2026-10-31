package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concepts grillage : ce que SAM doit suivre pour dire si la clôture qui
 * barre le chemin est fermée ou ouverte ([FenceOpeningSignal]).
 *
 * `fence` et `gate` sont déjà suivis en preset extérieur et dans les défauts,
 * `barrier` en preset extérieur seul (31/10 : plus dans les défauts, slot à
 * « keys », ordre user) : aucun prompt à ajouter, [PROMPTS] ne sert qu'au
 * branchement. Le français reste reconnu : « grillage » compte comme « fence ».
 *
 * Distances : le sténopé ([KnownSizes], barrière 1,00 m, clôture 1,50 m,
 * portail 1,75 m) mesure ces trois concepts — un grillage sans mesure reste
 * muet ([FenceOpeningSignal]).
 */
object FenceOpeningConcepts {

    /**
     * Concepts à suivre côté SAM : 3 prompts, chacun sous
     * [dev.sam3vision.core.live.LivePrompts.MAX_LENGTH] et bien sous
     * `PROMPT_LIMIT = 18` comme [dev.sam3vision.core.live.LivePrompts.MAX].
     */
    val PROMPTS: List<String> = listOf("fence", "barrier", "gate")

    private val KEYS = setOf(
        "fence", "cloture", "grillage",
        "barrier", "barriere",
        "gate", "portail",
    )

    /** Formes canoniques du portail : c'est lui qui s'ouvre ([isGate]). */
    private val GATES = setOf("gate", "portail")

    /** Vrai si [label] désigne une clôture suivie (casse, accents, pluriel, composés neutralisés). */
    fun isFence(label: String): Boolean = matches(label, KEYS)

    /**
     * Vrai si [label] désigne un portail : une ouverture entre portails
     * s'annonce « Portail ouvert … », entre grillages « Ouverture … ».
     */
    fun isGate(label: String): Boolean = matches(label, GATES)

    /**
     * Hauteur réelle typique de [label] (`null` si inconnue) : le sténopé qui
     * mesure la clôture (barrière 1,00 m, clôture 1,50 m, portail 1,75 m).
     */
    fun heightMeters(label: String): Float? = KnownSizes.heightMeters(label)

    /** [label] canonique dans [keys] : entier, pluriel simple, ou un de ses mots. */
    private fun matches(label: String, keys: Set<String>): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in keys) return true
        if (key.endsWith("s") && key.length > 3 && key.dropLast(1) in keys) return true
        return key.split(' ').any { it in keys }
    }
}
