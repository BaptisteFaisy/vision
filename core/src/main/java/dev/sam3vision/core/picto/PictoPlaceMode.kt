package dev.sam3vision.core.picto

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Lieu du picto iSee : DEDANS (l'appartement, pictos suivis et guidés) ou
 * DEHORS (la rue, pas de pictos — la traversée et les dangers y parlent).
 * Exclusif : on est soit dedans, soit dehors, jamais les deux. Pur et testé
 * en JVM.
 */
enum class PictoPlaceMode {
    DEDANS,
    DEHORS,
    ;

    /** Vrai seulement dedans : dehors, les pictos ne sont ni suivis ni guidés. */
    val pictoEnabled: Boolean get() = this == DEDANS

    companion object {
        /** Lieu d'ouverture : dehors — la rue (obstacles, GPS, traversée) parle au lancement, le silence dehors est un danger (01/11/2026). */
        val DEFAULT: PictoPlaceMode = DEHORS
    }
}

/**
 * Ordre vocal de bascule dedans/dehors (« mode dehors », « indoor mode ») :
 * explicite seulement — le mot « mode » plus un mot de lieu, sinon la phrase
 * part au LLM comme avant. Appelé sur le texte dicté avant le LLM, comme
 * l'ordre d'entrée en mode Maison — une commande ne fait jamais d'appel
 * réseau.
 *
 * Tolérant : casse, accents et ponctuation neutralisés par
 * [KnownSizes.canonical]. Pur et testé en JVM.
 */
object PlaceModeOrder {

    /** Mots de lieu « dedans » (canoniques, français et anglais). */
    private val INDOOR_WORDS = setOf("dedans", "interieur", "indoor")

    /** Mots de lieu « dehors » (canoniques, français et anglais). */
    private val OUTDOOR_WORDS = setOf("dehors", "exterieur", "outdoor")

    /**
     * Lieu ordonné par [text], `null` si ce n'en est pas un (ni « mode », ni
     * mot de lieu — ou les deux lieux à la fois, ordre ambigu refusé).
     */
    fun parse(text: String): PictoPlaceMode? {
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }.toSet()
        if (!words.contains("mode")) return null
        val indoor = INDOOR_WORDS.any { words.contains(it) }
        val outdoor = OUTDOOR_WORDS.any { words.contains(it) }
        if (indoor == outdoor) return null
        return if (indoor) PictoPlaceMode.DEDANS else PictoPlaceMode.DEHORS
    }
}

/**
 * Confirmations parlées de bascule dedans/dehors, dites par la voix des
 * positions. Pures et testées en JVM.
 */
object PlaceModeText {

    /** La bascule a eu lieu : le dire, dans la langue de la voix. */
    fun switched(mode: PictoPlaceMode, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.Fr -> when (mode) {
                PictoPlaceMode.DEDANS -> "Dedans."
                PictoPlaceMode.DEHORS -> "Dehors."
            }
            SpeechLanguage.En -> when (mode) {
                PictoPlaceMode.DEDANS -> "Inside."
                PictoPlaceMode.DEHORS -> "Outside."
            }
        }
}
