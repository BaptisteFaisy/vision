package dev.sam3vision.app.ihear

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.distance.LensSpec
import java.io.File

/** Le seul écran iSee : le chat plein écran, la caméra vit dans une fenêtre flottante. */

/** Une bulle de l'écran chat : le texte du user, celui lu à voix haute, ou l'intro. */
sealed interface ChatEntry {
    val id: Long
    val text: String
    val audioPath: String?

    data class UserBubble(override val id: Long, override val text: String, override val audioPath: String?) : ChatEntry
    data class AiBubble(override val id: Long, override val text: String, override val audioPath: String?) : ChatEntry

    /** La bulle d'intro : posée en tête du chat pendant la première écoute. */
    data class IntroBubble(override val id: Long, override val text: String) : ChatEntry {
        override val audioPath: String? get() = null
    }
}

/** État complet de l'écran iSee (chat + fenêtre caméra). */
data class IhearUiState(
    val entries: List<ChatEntry> = emptyList(),

    /** La bulle d'intro est posée en tête du chat tant que l'audio pousse (1re ouverture). */
    val introVisible: Boolean = false,

    /** Dernière photo du double tap, affichée dans la fenêtre flottante avant d'évaporer. */
    val photoPath: String? = null,

    /** Pastilles de distance posées sur cette photo ; `null` = rien de mesurable. */
    val distanceLayer: DistanceLayer? = null,

    /** Bulle affichée : index dans les bulles affichées, intro comprise (`0` = l'intro).
     * Il est toujours posé sur la dernière bulle : il n'y a plus de navigation. */
    val selected: Int = INTRO_SELECTION,
    val holding: Boolean = false,
    val transcribing: Boolean = false,
    val thinking: Boolean = false,
    val speakingEntryId: Long? = null,
    val recordingVideo: Boolean = false,
    val status: String? = null,
)

// ————— Sélection : toujours la dernière bulle (aucune navigation entre messages) —————

/**
 * Bulles à l'écran : l'historique plus l'intro, toujours première et jamais
 * retirée (seul son audio se coupe par la voix).
 */
fun bubbleCount(entriesSize: Int): Int = entriesSize + 1

/**
 * Défilement à jouer pour que la bulle `[top, bottom]` (pixels écran) soit entière
 * dans la bande lisible `[headerPx, sea]` : le strict complément, `0` si elle y est
 * déjà. Négatif = faire descendre le fil vers les bulles plus anciennes.
 */
fun revealShift(top: Int, bottom: Int, headerPx: Int, sea: Int): Float = when {
    top < headerPx -> (top - headerPx).toFloat()
    bottom > sea -> (bottom - sea).toFloat()
    else -> 0f
}

/** Les actions que seule la caméra de la fenêtre flottante peut exécuter. */
interface IhearCameraActions {
    val ready: Boolean

    /** Calibrage optique du capteur lié (focale + taille physique), `null` si illisible. */
    val lensSpec: LensSpec? get() = null
    fun startRecording(file: File): Boolean
    suspend fun stopRecording(): File?

    /**
     * Photo immédiate du double tap ; `true` = demande acceptée. [onSaved] est rappelé
     * plus tard sur le thread principal : `true` si la photo est écrite, `false` sinon.
     */
    fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean
}

/** ID de la bulle d'intro : en dehors du domaine des lignes Room. */
const val INTRO_BUBBLE_ID = -2L

/** ID de la confirmation de vitesse : pas une bulle, mais une lecture à part entière. */
const val SPEED_BUBBLE_ID = -3L

/** Sélection de l'intro : elle occupe toujours la première bulle du chat. */
const val INTRO_SELECTION = 0

/** Les bulles (2 possibles par ligne) sont distinguées par un décalage sûr. */
const val BUBBLE_STRIDE = 1_000_000L

/** ID d'affichage d'une bulle : ligne Room × décalage, + 1 pour la réponse IA. */
fun bubbleId(rowId: Long, ai: Boolean): Long = rowId * BUBBLE_STRIDE + if (ai) 1L else 0L

/** Marqueur audio d'une transcription : « [silence] », « [outro jingle] »… */
private val AUDIO_MARKER = Regex("\\[[^\\]]*]").toPattern()

/**
 * Vrai si le texte vaut la peine d'être synthétisé : une fois les marqueurs audio
 * retirés, il reste au moins une lettre ou un chiffre. ElevenLabs retire lui aussi ces
 * marqueurs et répond 400 « empty text » sur une transcription qui n'en contient que
 * (mesuré sur le téléphone : « Voice: erreur HTTP 400 »).
 */
fun speakableText(text: String): Boolean =
    AUDIO_MARKER.matcher(text).replaceAll(" ").any { it.isLetterOrDigit() }
