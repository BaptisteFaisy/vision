package dev.sam3vision.app.voicenav

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Voix GPS dédiée (SPEC v4) : synthèse réseau ElevenLabs, jamais de clips.
 *
 * - manœuvres du moteur (niveau 11) : phrase de [VoiceNavEngineDriver.say] ;
 * - rappel (niveau 3) : [gpsReminderPhrase] toutes les [GPS_REMINDER_MS].
 *
 * Pur JVM : testé sans Android.
 */

/** Cadence du rappel GPS : silence depuis 15 s ou plus (SPEC v4 §3). */
const val GPS_REMINDER_MS = 15_000L

/**
 * Rappel GPS : dernière consigne répétée + distance restante, en anglais.
 * [instruction] blanche (aucun événement moteur encore parlé) : repli ligne
 * droite — le rappel ne se tait jamais sur une consigne vide.
 */
fun gpsReminderPhrase(instruction: String, remainingMeters: Long): String {
    val clean = instruction.trim().trimEnd('.').ifBlank { "Continue straight ahead" }
    val distance = DistanceText.spoken(remainingMeters.coerceAtLeast(0).toFloat(), SpeechLanguage.En)
    return "$clean. $distance remaining."
}

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
