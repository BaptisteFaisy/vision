package dev.sam3vision.app.glasses

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Choix de la sortie audio de la voix, sans Android pour la décision : une liste de
 * candidats en entrée, un candidat (ou rien) en sortie.
 *
 * Le code Android ([GlassesVoiceRoute] en production, `bench/GlassesVoice` pour le
 * banc) ne décide pas : il lit `AudioManager`, remplit des [AudioOutput] et applique
 * le choix. C'est ce qui permet de prouver ici, sur JVM, les deux règles qui
 * comptent — **une paire de lunettes nommée passe avant n'importe quelle autre
 * sortie**, et **un appareil qui n'est pas un puits de son n'est jamais choisi**
 * (le téléphone se présente parfois lui-même comme une sortie Bluetooth : s'y
 * router fait disparaître la voix sans aucune erreur).
 */

/**
 * Une sortie candidate, réduite à ce qui décide.
 *
 * @param name nom vu par l'utilisateur (`AudioDeviceInfo.productName`) : c'est ce
 *   qui permet de reconnaître les lunettes parmi les autres sorties Bluetooth.
 * @param type type `AudioDeviceInfo` (7, 8, 26, 27…) — gardé pour le journal.
 * @param isSink vrai si l'appareil peut **recevoir** du son (`AudioDeviceInfo.isSink`).
 * @param rank préférence du type : petit = meilleur, calculé par [rankOf].
 */
internal data class AudioOutput(
    val name: String,
    val type: Int,
    val isSink: Boolean,
    val rank: Int,
)

/**
 * Sortie à utiliser pour la voix : celle dont le nom correspond à [preferredName]
 * (la paire que le SDK DAT connaît) si elle existe, sinon la meilleure du lot.
 * `null` = aucune sortie Bluetooth utilisable, on reste sur la sortie par défaut.
 *
 * La correspondance est partielle et insensible à la casse : le nom du SDK
 * (« Ray-Ban Meta ») et celui de la pile Bluetooth du téléphone (« Ray-Ban Meta
 * Hands-Free ») ne sont pas toujours identiques au caractère près.
 */
internal fun pickBluetoothOutput(
    candidates: List<AudioOutput>,
    preferredName: String?,
): AudioOutput? {
    val sinks = candidates.filter { it.isSink }
    preferredName?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted ->
        sinks.firstOrNull { it.name.contains(wanted, ignoreCase = true) }?.let { return it }
    }
    return sinks.minByOrNull { it.rank }
}

/** Ce qui peut sortir du son vers une paire : BLE d'abord, A2DP (la musique des lunettes) ensuite. */
internal fun rankOf(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> 1
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
    AudioDeviceInfo.TYPE_HEARING_AID -> 3
    else -> 4
}

/** Types qui peuvent recevoir la voix d'une paire : casque BLE, enceinte BLE, A2DP, SCO, aide auditive. */
@Suppress("InlinedApi")
internal fun isBluetoothOutput(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
    type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
    type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
    type == AudioDeviceInfo.TYPE_HEARING_AID

/**
 * Sorties Bluetooth réellement proposées par la plateforme, avec leur objet Android
 * d'origine : la partie « lire `AudioManager` » commune à la production et au banc.
 * Ne lève jamais — un `AudioManager` absent rend une liste vide, donc la sortie par
 * défaut du téléphone.
 */
internal fun bluetoothOutputs(manager: AudioManager): List<Pair<AudioOutput, AudioDeviceInfo>> =
    runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
        .getOrDefault(emptyList())
        .filter { isBluetoothOutput(it.type) }
        .map { device ->
            AudioOutput(
                name = device.productName?.toString().orEmpty(),
                type = device.type,
                isSink = device.isSink,
                rank = rankOf(device.type),
            ) to device
        }
