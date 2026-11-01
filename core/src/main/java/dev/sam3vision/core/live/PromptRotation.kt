package dev.sam3vision.core.live

/**
 * Rotation des concepts du direct : la sonde envoie 16 concepts à 30 Hz et le
 * post-traitement serveur (~67 ms) domine la latence ; en 3 groupes de 6, le
 * poste tombe à ~39 ms (bench A100 budget-8, batch-7 graphé). [split] découpe
 * une fois par configuration, [groupFor] choisit le groupe de la frame.
 *
 * [ALWAYS_CONCEPT] est dans **chaque** groupe (ordre user : `person` à chaque
 * frame) ; le reste tourne en round-robin, chaque concept non-personne étant
 * rafraîchi à ~10 Hz. Le client [ConceptAccumulator] fusionne les réponses :
 * voix, overlay et Traversée lisent l'accumulé, jamais le groupe seul.
 */
object PromptRotation {

    /** Groupes par cycle : 3 frames couvrent tous les concepts. */
    const val GROUPS = 3

    /**
     * Concept présent dans chaque groupe : la personne (l'obstacle qui tue, le
     * veto Traversée, la foule) reste à plein régime pendant que le reste tourne.
     */
    const val ALWAYS_CONCEPT = "person"

    /**
     * Découpe [prompts] en [groups] groupes : les concepts [always] (comparaison
     * insensible à la casse) dans chacun, le reste en round-robin dans l'ordre
     * reçu. Vide et doublons écartés comme [LivePrompts.normalize], sans retomber
     * sur le défaut — l'appelant garde sa liste complète en repli. Pur.
     */
    fun split(
        prompts: List<String>,
        groups: Int = GROUPS,
        always: List<String> = listOf(ALWAYS_CONCEPT),
    ): List<List<String>> {
        require(groups >= 1) { "rotation sans groupe" }
        val cleaned = prompts.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val sticky = always.map { it.trim().lowercase() }.toSet()
        val (pinned, rest) = cleaned.partition { it.lowercase() in sticky }
        val buckets = List(groups) { mutableListOf<String>() }
        rest.forEachIndexed { index, concept -> buckets[index % groups] += concept }
        return buckets.map { pinned + it }
    }

    /**
     * Groupe de la frame [seq] : 0, 1, 2, 0… Un groupe vide (liste d'un seul
     * concept hors [ALWAYS_CONCEPT]) rend vide — l'appelant renvoie alors sa
     * liste complète, jamais un groupe vide au serveur.
     */
    fun groupFor(split: List<List<String>>, seq: Long): List<String> {
        if (split.isEmpty()) return emptyList()
        val index = ((seq % split.size) + split.size).rem(split.size).toInt()
        return split[index]
    }
}
