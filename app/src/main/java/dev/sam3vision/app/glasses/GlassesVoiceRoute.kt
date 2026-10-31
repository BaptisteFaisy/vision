package dev.sam3vision.app.glasses

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Où sort la voix de l'app quand les lunettes sont en session : dans les lunettes.
 *
 * Le banc avait prouvé, sur la paire réelle, que la sortie Bluetooth A2DP de la
 * paire est utilisable (`bt_a2dp`, 27/10) ; la production, elle, jouait tout sur le
 * haut-parleur du téléphone. Ce point d'entrée comble ce seul écart : il retient si
 * une session lunettes est ouverte ([follow]) et, à chaque phrase, relit les sorties
 * audio pour rendre celle de la paire ([current]).
 *
 * Trois choix, tous assumés :
 * - **relire `AudioManager` à chaque phrase** : la paire n'apparaît en Bluetooth
 *   qu'après le montage du lien (2 à 10 s), une sortie lue une fois au démarrage
 *   serait souvent vide ;
 * - **ne rien faire hors session** : sans lunettes, la voix reste exactement là où
 *   elle était (haut-parleur, écouteurs filaires, sortie choisie par l'utilisateur) ;
 * - **ne jamais lever** : un `AudioManager` absent ou un appareil refusé rend `null`,
 *   et le lecteur garde sa sortie par défaut — un échec de routage ne doit pas
 *   rendre l'app muette.
 *
 * Le choix lui-même (nom de la paire d'abord, puits de son seulement) est pur et
 * testé en JVM dans [pickBluetoothOutput].
 */
object GlassesVoiceRoute {

    /** Journal de la chaîne voix (recette : `adb logcat -s GlassesVoice`). */
    private const val TAG = "GlassesVoice"

    /** Dernier état lunettes reçu ; seuls « connecté » et le nom de la paire servent. */
    @Volatile
    private var glasses: GlassesUiState = GlassesUiState()

    /** Dernière décision journalisée : une ligne au changement, pas une par phrase. */
    @Volatile
    private var logged: String? = null

    /**
     * Recopie l'état lunettes. Appelé à chaque changement par le chef d'orchestre,
     * donc l'ouverture **et** la fermeture de session sont vues.
     */
    fun follow(state: GlassesUiState) {
        glasses = state
    }

    /** Fin de vie du chef d'orchestre : plus aucune session, donc plus de routage. */
    fun forget() {
        glasses = GlassesUiState()
        logged = null
    }

    /** Vrai quand la voix doit viser la paire : session ouverte (`STARTED`). */
    val targetsGlasses: Boolean get() = glasses.connected

    /**
     * Sortie à préférer pour la phrase suivante, ou `null` pour laisser le lecteur
     * sur la sortie par défaut du téléphone. Relu à chaque lecture.
     */
    fun current(context: Context): AudioDeviceInfo? {
        if (!targetsGlasses) return null
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
            ?: return log(null, "voice: speaker (AudioManager unavailable)")
        val candidates = bluetoothOutputs(manager)
        val picked = pickBluetoothOutput(candidates.map { it.first }, glasses.deviceName)
        if (picked == null) return log(null, "voice: speaker — no usable Bluetooth output")
        val device = candidates.first { it.first == picked }.second
        return log(device, "voice: Bluetooth « ${picked.name} » (type ${picked.type})")
    }

    /** Journalise une décision, et seulement quand elle change (une phrase = zero ligne). */
    private fun log(device: AudioDeviceInfo?, line: String): AudioDeviceInfo? {
        if (logged != line) {
            logged = line
            Log.i(TAG, line)
        }
        return device
    }
}
