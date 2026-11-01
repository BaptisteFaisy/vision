package dev.sam3vision.app.isee

import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.TwinLiteWord
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.traverse.VehicleStateText
import java.io.File

/**
 * Phrase parlée → clip **enregistré** du pack voix (ordre user du 01/11).
 *
 * Ce qui n'a plus de position n'a plus de variable : « Voiture en mouvement. »
 * et « Stop, car moving. » sont fixes, donc joués depuis le pack (mp3
 * embarqué, < 100 ms, zéro réseau) au lieu d'être synthétisés. Une phrase qui
 * porte encore une mesure (distance, heure) ou un compte (pluriel) n'a pas de
 * clip : synthèse, comme avant.
 */
internal fun spokenClipId(text: String): String? =
    GuidanceWord.clipIdFor(text) ?: TwinLiteWord.clipIdFor(text) ?: recordedClipId(text)

/** Clips d'état véhicules : `null` = phrase variable, à synthétiser. */
internal fun recordedClipId(text: String): String? =
    VehicleStateText.clipIdFor(text)

/** Fichier du clip, servi par le pack quand la voix réglée est la sienne (`null` = synthèse). */
internal fun recordedFileFor(phrase: String, current: Settings, clips: VoicePack?): File? =
    recordedClipId(phrase)
        ?.let { clips?.clipFor(it, current.speechLanguage, current.ttsVoiceId) }
        ?.takeIf { it.isFile }
