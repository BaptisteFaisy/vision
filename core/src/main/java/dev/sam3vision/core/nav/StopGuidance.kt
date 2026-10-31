package dev.sam3vision.core.nav

/** Résultat du parseur d'ordres GPS ([StopGuidanceParser]). */
enum class GuidanceOrder {
    /** Ni arrêt, ni désactivation, ni activation : ce n'est pas un ordre GPS. */
    NONE,

    /** « Arrête le guidage / le GPS » : couper le guidage en cours. */
    STOP,

    /** « Désactive le GPS » : couper le guidage **et** désactiver la fonction. */
    DISABLE,

    /** « Active le GPS » : réactiver la fonction (le guidage reprendra à la demande). */
    ENABLE,
}

/**
 * Détecteur déterministe des ordres GPS (français et anglais oraux).
 *
 * Formes reconnues, insensibles à la casse et aux accents : un verbe
 * (« arrête », « arrêter », « stop », « stoppe », « annule », « annuler »,
 * « désactive », « désactiver », « active », « réactive ») suivi ou précédé
 * d'un nom de guidage (« guidage », « navigation », « itinéraire », « GPS »,
 * avec ou sans accent). Exemples : « arrête le guidage », « stop la
 * navigation », « annule l'itinéraire », « désactive le GPS », « active le guidage ».
 * Anglais : « stop/cancel the navigation », « disable/deactivate the GPS »,
 * « turn/switch the GPS off/on », « enable the guidance ».
 *
 * Le verbe seul (« arrête ! ») ne suffit pas : il peut viser la voix ou la
 * musique — exiger le nom évite de couper un guidage par mégarde. Comme
 * [NavigationIntentParser] : pur, sans dépendance, ne lève jamais.
 */
object StopGuidanceParser {

    private val STOP_VERBS =
        setOf("arrete", "arretes", "arreter", "stop", "stoppe", "stoppes", "stopper", "annule", "annules", "annuler", "cancel", "cancels", "cancelled")

    private val DISABLE_VERBS =
        setOf("desactive", "desactives", "desactiver", "desactivation", "eteins", "eteindre", "coupe", "couper", "disable", "disables", "deactivate", "deactivates")

    private val ENABLE_VERBS =
        setOf("active", "actives", "activer", "reactive", "reactives", "reactiver", "reallume", "rallume", "enable", "enables", "reactivate")

    private val NOUNS = setOf("guidage", "guidance", "navigation", "itineraire", "route", "gps")

    /** Ordre porté par [text], ou [GuidanceOrder.NONE]. */
    fun parse(text: String): GuidanceOrder {
        if (text.isBlank()) return GuidanceOrder.NONE
        val words = foldAccents(text).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val hasNoun = words.any { it in NOUNS }
        if (!hasNoun) return GuidanceOrder.NONE
        if (words.contains("turn") || words.contains("switch")) {
            if (words.contains("off")) return GuidanceOrder.DISABLE
            if (words.contains("on")) return GuidanceOrder.ENABLE
        }
        return when {
            // La désactivation prime : « arrête **et** désactive » coupe tout.
            words.any { it in DISABLE_VERBS } -> GuidanceOrder.DISABLE
            words.any { it in STOP_VERBS } -> GuidanceOrder.STOP
            words.any { it in ENABLE_VERBS } -> GuidanceOrder.ENABLE
            else -> GuidanceOrder.NONE
        }
    }

    /** Vrai si [text] ordonne d'arrêter le guidage en cours. */
    fun isStop(text: String): Boolean = parse(text) == GuidanceOrder.STOP

    /** Vrai si [text] ordonne de désactiver le GPS (couper **et** éteindre). */
    fun isDisable(text: String): Boolean = parse(text) == GuidanceOrder.DISABLE

    /** Vrai si [text] ordonne de réactiver le GPS. */
    fun isEnable(text: String): Boolean = parse(text) == GuidanceOrder.ENABLE
}
