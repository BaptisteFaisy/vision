package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.speech.SpeechLanguage

/** Cap de marche demandé à la voix : la direction où l'utilisateur veut aller. */
enum class SteeringCap { Left, Straight, Right }

/**
 * Ordre vocal de cap : « va à gauche », « tourne à droite », « tout droit ».
 *
 * Appelé sur le texte dicté **avant** le routeur navigation Maps : « va à
 * gauche » matche aussi « va à X » (`NavigationIntentParser.VA`) et ouvrirait
 * Maps sans cette priorité (verrouillé par `SteeringTest.ordre cap prioritaire
 * sur l'intention Maps`). Comme le mode Traversée, une commande ne fait jamais
 * d'appel réseau et rend `null` quand ce n'en est pas une.
 *
 * Un cap exige un **verbe de mouvement** : « qu'est-ce qu'il y a à gauche ? »
 * est une question (elle part au LLM), pas un ordre. Deux directions dans la
 * même phrase (« va à gauche et à droite ») = ambigu, `null`.
 *
 * Tolérant comme le mode Traversée : casse, accents et ponctuation neutralisés
 * par [KnownSizes.canonical]. Pur et testé en JVM.
 */
object SteeringCommand {

    private val LEFT = setOf("gauche", "left")
    private val RIGHT = setOf("droite", "right")
    private val STRAIGHT_WORDS = setOf("straight", "ahead")
    private val STRAIGHT_BIGRAMS = setOf("tout droit", "droit devant")

    /** Verbes de mouvement (canoniques) : sans eux, pas d'ordre de cap. */
    private val VERBS = setOf(
        "va", "vas", "aller", "allez", "allons", "pars", "partir",
        "tourne", "tourner", "tournez", "tournons",
        "prends", "prendre", "prenez", "prenons",
        "guide", "guider", "dirige", "diriger",
        "continue", "continuer", "avance", "avancer",
        "marche", "marcher", "marchons",
        "go", "goes", "going", "turn", "turns", "take", "walk", "head", "continue", "steer",
    )

    /** Cap porté par [text] dicté, `null` si ce n'en est pas un. */
    fun parse(text: String): SteeringCap? {
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
        if (words.none { VERBS.contains(it) }) return null
        val left = words.any { LEFT.contains(it) }
        val right = words.any { RIGHT.contains(it) }
        val straight = words.any { STRAIGHT_WORDS.contains(it) } || hasStraightBigram(words)
        if (listOf(left, right, straight).count { it } != 1) return null
        return when {
            left -> SteeringCap.Left
            right -> SteeringCap.Right
            else -> SteeringCap.Straight
        }
    }

    /** « tout droit » / « droit devant » : le cap droit ne tient qu'en deux mots. */
    private fun hasStraightBigram(words: List<String>): Boolean =
        words.zipWithNext().any { (first, second) -> STRAIGHT_BIGRAMS.contains("$first $second") }
}

/**
 * Confirmation parlée du cap (« Je te guide vers la gauche. »), dite par le
 * même chemin audio que la navigation. Quand le guidage est coupé dans les
 * Réglages, le cap est mémorisé mais la phrase le dit — jamais de silence
 * trompeur. Pure et testée en JVM.
 */
object SteeringText {

    fun confirm(
        cap: SteeringCap,
        guided: Boolean,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String = when (language) {
        SpeechLanguage.Fr -> confirmFrench(cap, guided)
        SpeechLanguage.En -> confirmEnglish(cap, guided)
    }

    private fun confirmFrench(cap: SteeringCap, guided: Boolean): String {
        if (!guided) return "Cap ${frenchCap(cap)}. Active le guidage dans les Réglages."
        return when (cap) {
            SteeringCap.Left -> "Je te guide vers la gauche."
            SteeringCap.Straight -> "Je te guide tout droit."
            SteeringCap.Right -> "Je te guide vers la droite."
        }
    }

    private fun confirmEnglish(cap: SteeringCap, guided: Boolean): String {
        if (!guided) return "Heading ${englishCap(cap)}. Turn guidance on in Settings."
        return when (cap) {
            SteeringCap.Left -> "Guiding you left."
            SteeringCap.Straight -> "Guiding you straight ahead."
            SteeringCap.Right -> "Guiding you right."
        }
    }

    private fun frenchCap(cap: SteeringCap): String = when (cap) {
        SteeringCap.Left -> "à gauche"
        SteeringCap.Straight -> "tout droit"
        SteeringCap.Right -> "à droite"
    }

    private fun englishCap(cap: SteeringCap): String = when (cap) {
        SteeringCap.Left -> "left"
        SteeringCap.Straight -> "straight ahead"
        SteeringCap.Right -> "right"
    }
}

/**
 * Chemin calculé pour la frame : le [cap] demandé, s'il est [blocked], et le
 * [passage] conseillé (toujours renseigné quand c'est bouché, `null` sur voie
 * libre). Vit dans l'état de la sonde : la voix le dit, la vidéo le dessine.
 */
data class SteeringCue(
    val cap: SteeringCap,
    val blocked: Boolean,
    val passage: SteeringCap?,
) {
    /** Direction de la flèche sur la vidéo : le passage si bouché, le cap sinon. */
    fun arrowDir(): SteeringCap = passage ?: cap

    companion object {
        fun from(decision: GuidanceDecision): SteeringCue =
            SteeringCue(decision.cap, decision.blocked, decision.passage)
    }
}
