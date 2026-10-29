package dev.sam3vision.core.nav

/**
 * Détecteur déterministe de l'ordre « arrêter le guidage » (français oral).
 *
 * Formes reconnues, insensibles à la casse et aux accents : un verbe d'arrêt
 * (« arrête », « arrêter », « stop », « stoppe », « annule », « annuler »)
 * suivi ou précédé d'un nom de guidage (« guidage », « navigation »,
 * « itinéraire », avec ou sans accent). Exemples : « arrête le guidage »,
 * « stop la navigation », « annule l'itinéraire ».
 *
 * Le verbe seul (« arrête ! ») ne suffit pas : il peut viser la voix ou la
 * musique — exiger le nom évite de couper un guidage par mégarde. Comme
 * [NavigationIntentParser] : pur, sans dépendance, ne lève jamais.
 */
object StopGuidanceParser {

    private val VERBS = setOf("arrete", "arreter", "stop", "stoppe", "stopper", "annule", "annuler")

    private val NOUNS = setOf("guidage", "navigation", "itineraire")

    /** Vrai si [text] ordonne d'arrêter le guidage en cours. */
    fun isStop(text: String): Boolean {
        if (text.isBlank()) return false
        val words = foldAccents(text).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return words.any { it in VERBS } && words.any { it in NOUNS }
    }
}
