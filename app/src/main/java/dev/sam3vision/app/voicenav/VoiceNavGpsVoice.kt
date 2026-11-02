package dev.sam3vision.app.voicenav

/**
 * Voix GPS dédiée (SPEC v4) : les mots de manœuvre (« Left. »/« Right. »)
 * partent en clips offline (<100 ms), jamais en synthèse.
 *
 * - manœuvres du moteur (niveau 11) : phrase de [VoiceNavEngineDriver.say] ;
 * - rappel (niveau 3) : mot redit toutes les [GPS_REMINDER_MS].
 *
 * Pur JVM : testé sans Android.
 */

/** Cadence du rappel GPS : silence depuis 15 s ou plus (SPEC v4 §3). */
const val GPS_REMINDER_MS = 15_000L

/**
 * Garde voix-route : la parole route marque le lecteur partagé comme occupé.
 *
 * Voix réseau câblée ([networkWired], manœuvres + rappel en ElevenLabs via le
 * lecteur partagé) : le TTS système ne parle jamais, son état est un faux
 * permanent — seule compte la lecture réelle ([readerBusy]). Voix système
 * ([networkWired] faux) : le haut-parleur local fait foi ([speakerActive]).
 * Pure, testée en JVM.
 */
internal fun routeSpeechActive(networkWired: Boolean, readerBusy: Boolean, speakerActive: Boolean): Boolean =
    if (networkWired) readerBusy else speakerActive
