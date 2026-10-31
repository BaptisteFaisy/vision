package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes

/** Ordre vocal du mode Traversée : l'activer ou le couper. */
enum class CrossingVoiceAction { Activate, Deactivate }

/**
 * Ordre vocal du mode Traversée : « traverser », « crossing mode on » ou
 * « crosswalk on » l'active, « arrête la traversée », « crossing mode off »
 * ou « crosswalk off » le coupe. Appelé sur le texte dicté avant l'envoi
 * au LLM, comme la phrase de coupe de l'intro — une commande ne fait
 * jamais un appel réseau.
 *
 * Tolérant : casse, accents et ponctuation neutralisés par
 * [KnownSizes.canonical]. Le « travers » nu de « à travers la pièce » ne
 * déclenche **pas** : seules les formes verbales et nominales comptent.
 * « crosswalk » seul n'est **pas** un ordre : le guidage dit lui-même
 * « Crosswalk in 50 meters » et « On the crosswalk. », et le micro peut
 * réentendre le haut-parleur — seul « on »/« off » **après** « crosswalk »
 * compte (« crosswalk on », « turn the crosswalk off »). L'ASR coupe
 * parfois le mot (« cross walk on ») : recollé avant tout, avec la même
 * règle — sinon « cross » seul déclencherait, y compris sur
 * « cross walk in 50 meters » réentendu.
 * Pur et testé en JVM.
 */
object CrossingVoiceCommand {

    /** Formes qui désignent la traversée (canoniques, voir [KnownSizes.canonical]). */
    private val CROSS_WORDS = setOf(
        "traverser", "traverse", "traverses", "traversent",
        "traversons", "traversez", "traversee",
        "cross", "crossing",
    )

    /** Formes qui coupent le mode (« stop traverser », « arrête la traversée », « crossing mode off »). */
    private val STOP_WORDS = setOf(
        "stop", "stoppe", "arrete", "arreter", "arret",
        "annule", "annuler", "coupe", "couper",
        "quitte", "quitter", "desactive", "desactiver", "cancel", "off",
    )

    /**
     * Ordre porté par [text] dicté, `null` si ce n'en est pas un (le texte
     * part alors au LLM comme avant).
     */
    fun parse(text: String): CrossingVoiceAction? {
        val words = glueCrosswalk(KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() })
        val walkAt = words.indexOf("crosswalk")
        if (walkAt >= 0) {
            val tail = words.drop(walkAt + 1)
            if (tail.contains("off")) return CrossingVoiceAction.Deactivate
            if (tail.contains("on")) return CrossingVoiceAction.Activate
        }
        if (words.none { CROSS_WORDS.contains(it) }) return null
        return if (words.any { STOP_WORDS.contains(it) }) CrossingVoiceAction.Deactivate
        else CrossingVoiceAction.Activate
    }

    /**
     * Recolle « cross walk » (coupure ASR) en « crosswalk », pour la règle
     * ci-dessus : « cross walk on/off » compte, « cross walk in 50 meters »
     * et « Find the cross walk » ne déclenchent pas.
     */
    private fun glueCrosswalk(words: List<String>): List<String> {
        val glued = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            if (i + 1 < words.size && words[i] == "cross" && words[i + 1] == "walk") {
                glued.add("crosswalk")
                i += 2
            } else {
                glued.add(words[i])
                i += 1
            }
        }
        return glued
    }
}
