package dev.sam3vision.app.glasses.bench

/**
 * Choix de la sortie audio de la voix, sans Android : une liste de candidats en
 * entrée, un candidat (ou rien) en sortie.
 *
 * Le code Android ([GlassesVoice]) ne décide pas : il lit `AudioManager`, remplit
 * des [AudioOutput] et applique le choix. C'est ce qui permet de prouver ici, sur
 * JVM, les deux règles qui comptent — **une paire de lunettes nommée passe avant
 * n'importe quelle autre sortie**, et **un appareil qui n'est pas un puits de son
 * n'est jamais choisi** (le téléphone se présente parfois lui-même comme une sortie
 * Bluetooth : s'y router fait disparaître la voix sans aucune erreur).
 *
 * Rien à réutiliser ailleurs dans `:app` : le seul lecteur de l'app (`SpeechPlayer`)
 * joue toujours sur la sortie par défaut et n'expose ni appareil ni `AudioDeviceInfo`
 * — la seule autre lecture d'`AudioManager` du dépôt est le `ToneGenerator` des
 * repères haptiques. Cette décision reste donc ici, et elle est seule à la prendre.
 */

/**
 * Une sortie candidate, réduite à ce qui décide.
 *
 * @param name nom vu par l'utilisateur (`AudioDeviceInfo.productName`) : c'est ce
 *   qui permet de reconnaître les lunettes parmi les autres sorties Bluetooth.
 * @param type type `AudioDeviceInfo` (7, 8, 26, 27…) — gardé pour le journal.
 * @param isSink vrai si l'appareil peut **recevoir** du son (`AudioDeviceInfo.isSink`).
 * @param rank préférence du type : petit = meilleur. Calculé par l'appelant, qui
 *   seul connaît les constantes de la plateforme.
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
