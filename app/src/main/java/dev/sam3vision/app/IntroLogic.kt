package dev.sam3vision.app

/**
 * Logique pure de l'intro vocale (hors Compose) : libellé de la bascule, visibilité
 * de la bulle du chat et phrase de coupe « Disable iSee intro ». Testée en JVM
 * (`app.UiLogicTest`).
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

/**
 * Vrai si la dictée demande la coupe de l'intro (« Disable iSee intro », sans casse).
 * La reconnaissance vocale écrit « iSee » en deux mots (« I see ») ou colle la
 * ponctuation : on compare donc sans espaces ni ponctuation.
 */
fun isIntroDisablePhrase(text: String): Boolean =
    text.lowercase().filter { it.isLetterOrDigit() }.contains("disableiseeintro")
