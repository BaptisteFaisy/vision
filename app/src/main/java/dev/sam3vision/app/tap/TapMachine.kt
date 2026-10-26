package dev.sam3vision.app.tap

/** Cycle de vie d'une session tap : rien, enregistrement, puis envoi à SAM 3.1. */
enum class TapPhase { IDLE, RECORDING, SENDING }

/** Ce que l'écran doit faire après un geste. `NONE` = geste ignoré, aucune annonce. */
enum class TapCommand { NONE, START, STOP_AND_SEND, IGNORE }

/** Instantané pur (aucune dépendance Android) : c'est l'écran qui mesure le temps. */
data class TapSnapshot(val phase: TapPhase, val elapsedMs: Long)

/**
 * Machine à états du tap court. Un seul tap par transition :
 * `IDLE → START`, `RECORDING → STOP_AND_SEND`, `SENDING → IGNORE` (muet : jamais « Failed »
 * pour ne pas superposer deux annonces).
 *
 * En dessous de [minRecordingMs] d'enregistrement, le clip est mécaniquement inutilisable
 * (CameraX finalize en erreur, fichier de 0 octet) : le tap ne déclenche **rien sur le coup**
 * mais n'est pas perdu — l'appelant le mémorise et [tickCommand] l'exécute dès que la durée
 * minimale est atteinte (~350 ms, imperceptible).
 */
fun tapCommand(snapshot: TapSnapshot, minRecordingMs: Long = TapConfig.MIN_RECORDING_MS): TapCommand =
    when (snapshot.phase) {
        TapPhase.IDLE -> TapCommand.START
        TapPhase.RECORDING -> if (snapshot.elapsedMs >= minRecordingMs) TapCommand.STOP_AND_SEND else TapCommand.NONE
        TapPhase.SENDING -> TapCommand.IGNORE
    }

/**
 * Battement : exécute un arrêt demandé trop tôt dès que l'enregistrement est assez long,
 * et arrête tout seul au-delà de [maxRecordingMs].
 */
fun tickCommand(
    snapshot: TapSnapshot,
    pendingStop: Boolean = false,
    maxRecordingMs: Long = TapConfig.MAX_RECORDING_MS,
    minRecordingMs: Long = TapConfig.MIN_RECORDING_MS,
): TapCommand = when {
    snapshot.phase != TapPhase.RECORDING -> TapCommand.NONE
    snapshot.elapsedMs >= maxRecordingMs -> TapCommand.STOP_AND_SEND
    pendingStop && snapshot.elapsedMs >= minRecordingMs -> TapCommand.STOP_AND_SEND
    else -> TapCommand.NONE
}

/** Vrai tant qu'une interaction est en cours : les taps suivants sont ignorés sans annonce. */
val TapPhase.busy: Boolean get() = this != TapPhase.IDLE

/**
 * Liste blanche des mots prononçables pendant l'interaction : toute autre phrase est
 * refusée par le lecteur, ce qui garantit que le TTS ElevenLabs du reste de l'app
 * (analyse, chat) ne se superpose pas au tap et ne couvre pas la piste audio.
 */
fun announcementAllowed(text: String, words: List<String> = TapConfig.words.map { it.spoken }): Boolean =
    text.trim() in words
