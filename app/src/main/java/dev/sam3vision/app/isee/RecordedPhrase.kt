package dev.sam3vision.app.isee

import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.TwinLiteWord
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.stairs.StairClips
import dev.sam3vision.core.traverse.VehicleStateText
import java.io.File

/**
 * Phrase parlée → clip **enregistré** du pack voix (ordre user du 01/11).
 *
 * Mots du guidage, mots TwinLite, états véhicules fixes, jalons d'escalier
 * EN, puis toute phrase égale à un texte du catalogue ([IseeClipsEn] :
 * trottoirs EN, « Stop. », objets trouvés…) : joués depuis le pack (mp3
 * embarqué, < 100 ms, zéro réseau) au lieu d'être synthétisés. Le reste
 * (mesure, heure, compte) part en synthèse réseau.
 */
internal fun spokenClipId(text: String): String? =
    GuidanceWord.clipIdFor(text) ?: TwinLiteWord.clipIdFor(text) ?: recordedClipId(text)
        ?: StairClips.clipIdFor(text) ?: IseeClipsEn.idForText(text)

/** Clips d'état véhicules : `null` = phrase variable, à synthétiser. */
internal fun recordedClipId(text: String): String? =
    VehicleStateText.clipIdFor(text)

/** Fichier du clip, servi par le pack quand la voix réglée est la sienne (`null` = synthèse). */
internal fun recordedFileFor(phrase: String, current: Settings, clips: VoicePack?): File? =
    recordedClipId(phrase)
        ?.let { clips?.clipFor(it, current.speechLanguage, current.ttsVoiceId) }
        ?.takeIf { it.isFile }
