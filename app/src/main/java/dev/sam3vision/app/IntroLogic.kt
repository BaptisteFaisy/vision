package dev.sam3vision.app

import dev.sam3vision.core.distance.KnownSizes

/**
 * Logique pure de l'intro vocale (hors Compose) : libellé de la bascule, visibilité
 * de la bulle du chat et phrases de coupe (« Disable iSee intro » en anglais,
 * « Désactive iSee intro » en français). Testée en JVM (`app.UiLogicTest`).
 */

/** Libellé de la bascule « intro » des Réglages. */
fun introToggleLabel(enabled: Boolean): String =
    if (enabled) "Intro à chaque ouverture : on" else "Intro à chaque ouverture : off"

/**
 * Le texte exact de l'intro, mot pour mot celui du MP3 embarqué (`intro_ihear.mp3`,
 * voix Jade US) : la bulle du chat affiche les phrases que la voix dit.
 * Le balayage vers le bas remonte aux bulles plus anciennes (`swipeStep`) : la phrase
 * du swipe est donc dite **avant** celle de la désactivation.
 */
val IHEAR_INTRO_TEXT = "Welcome to iSee. To record your surroundings, tap anywhere on " +
    "the screen to start the video, and tap again to stop and analyze it. To ask a " +
    "question or make a request, tap and hold while you speak, then release. To listen " +
    "to previous messages, swipe down. To stop hearing this intro, press and hold the " +
    "screen long enough to say, \"Disable iSee intro.\""

/**
 * La bulle d'intro (premier message du chat) est posée à chaque ouverture complète
 * quand les réglages sont lus et que l'intro est autorisée : seule sa coupure par
 * la voix (« Disable iSee intro ») la fait taire définitivement.
 */
fun introBubbleVisible(settingsReady: Boolean, introEnabled: Boolean): Boolean =
    settingsReady && introEnabled

/** Verbes de coupe reconnus (forme canonique, sans accent : voir [KnownSizes.canonical]). */
private val INTRO_VERBS = setOf("disable", "desactive", "desactiver")

/**
 * Vrai si la dictée demande la coupe de l'intro : « Disable iSee intro » en
 * anglais, « Désactive iSee intro » en français (l'interface est en français).
 * La reconnaissance vocale écrit « iSee » en deux mots (« I see »), garde
 * l'accent de « désactive » et colle la ponctuation : la comparaison passe
 * par la forme canonique (minuscules, sans accents ni ponctuation) et exige
 * un verbe de coupe **et** la mention « iSee intro » — une question sur
 * l'intro (« Que dit l'intro iSee ? ») ne coupe donc rien.
 */
fun isIntroDisablePhrase(text: String): Boolean {
    val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
    val introMention = words.indices.any { i ->
        (words[i] == "isee" && words.getOrNull(i + 1) == "intro") ||
            (words[i] == "i" && words.getOrNull(i + 1) == "see" && words.getOrNull(i + 2) == "intro")
    }
    return introMention && words.any { it in INTRO_VERBS }
}
