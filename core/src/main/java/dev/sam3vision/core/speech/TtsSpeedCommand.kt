package dev.sam3vision.core.speech

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.settings.Settings

/**
 * Ordre vocal de vitesse de la voix : « vitesse 1,5 », « parle plus vite »,
 * « ralentis », « faster », « vitesse normale ». Appelé sur le texte dicté
 * avant l'envoi au LLM, comme la traversée — une commande ne fait jamais
 * d'appel réseau.
 *
 * Tolérant : casse, accents et ponctuation neutralisés par
 * [KnownSizes.canonical], virgule décimale acceptée. Pur et testé en JVM.
 * Un « vite » nu (« décris vite la pièce ») ne déclenche **pas** : le relatif
 * exige « plus/moins » ou le verbe parler, comme « à travers » ne traverse pas.
 */
object TtsSpeedCommand {

    /** Pas d'un cran relatif (« plus vite », « moins vite »). */
    const val STEP = 0.2f

    /** Mots qui portent une valeur explicite (« vitesse 1,5 », « speed 1.5 »). */
    private val SPEED_WORDS = setOf("vitesse", "speed", "debit", "tempo")

    /** Valeur explicite après un mot de vitesse, virgule ou point (« vitesse 1,5 »). */
    private val EXPLICIT = """(?i)(vitesse|speed|d\u00e9bit|debit|tempo)\D{0,12}(\d+(?:[,.]\d+)?)""".toRegex()

    /** Formes qui accélèrent seules (« accélère », « faster »). */
    private val FASTER_ALONE = setOf("accelerer", "accelere", "accelerez", "faster", "quicker")

    /** Formes qui ralentissent seules (« ralentis », « slower »). */
    private val SLOWER_ALONE = setOf("ralentir", "ralenti", "ralentis", "ralentissez", "slower")

    /** Verbe parler : « parle vite » vise la voix, « décris vite » vise la réponse. */
    private val SPEAK_WORDS = setOf("parle", "parles", "parler", "parlez", "parlons", "speak", "talk")

    /** Formes qui reviennent à la vitesse normale (« vitesse normale »). */
    private val NORMAL_WORDS = setOf("normal", "normale", "normalement")

    /**
     * Nouvelle vitesse portée par [text] dicté depuis [current],
     * `null` si ce n'en est pas un (le texte part alors au LLM comme avant).
     */
    fun parse(text: String, current: Float = Settings.DEFAULT_TTS_SPEED): Float? {
        explicit(text)?.let { return it }
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }.toSet()
        if (words.isEmpty()) return null
        if (words.any { NORMAL_WORDS.contains(it) } && words.any { SPEED_WORDS.contains(it) }) return 1.0f
        if (faster(words)) return round1(current + STEP)
        if (slower(words)) return round1(current - STEP)
        return null
    }

    /** « parle plus vite », « faster », « speed up » — jamais « décris vite ». */
    private fun faster(words: Set<String>): Boolean =
        words.any { FASTER_ALONE.contains(it) } ||
            ("plus" in words && "vite" in words) ||
            (words.any { SPEAK_WORDS.contains(it) } && "vite" in words && "moins" !in words) ||
            ("speed" in words && "up" in words)

    /** « moins vite », « ralentis », « slow down » — jamais « c'est lent ». */
    private fun slower(words: Set<String>): Boolean =
        words.any { SLOWER_ALONE.contains(it) } ||
            ("moins" in words && "vite" in words) ||
            (words.any { SPEAK_WORDS.contains(it) } && ("lent" in words || "lentement" in words)) ||
            ("slow" in words && "down" in words)

    /** Valeur explicite lue dans le texte brut (la forme canonique mange la virgule). */
    private fun explicit(text: String): Float? {
        val raw = EXPLICIT.find(text)?.groupValues?.getOrNull(2) ?: return null
        return raw.replace(',', '.').toFloatOrNull()?.let(::round1)
    }

    /** Un cran ou une valeur, arrondi au dixième et borné (jamais hors 0,5..2). */
    private fun round1(value: Float): Float {
        val rounded = kotlin.math.round(value * 10) / 10f
        return Settings.clampTtsSpeed(rounded)
    }

    /** Confirmation dite et affichée (« Vitesse 1,5 », « Speed 1.5 »). */
    fun spoken(speed: Float, language: SpeechLanguage): String {
        val rounded = round1(speed)
        val digits = if (rounded == kotlin.math.floor(rounded)) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
        return if (language == SpeechLanguage.Fr) "Vitesse ${digits.replace('.', ',')}" else "Speed $digits"
    }
}
