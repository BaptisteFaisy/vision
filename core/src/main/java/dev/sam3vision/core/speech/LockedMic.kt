package dev.sam3vision.core.speech

import dev.sam3vision.core.usecase.DictationUseCase

/**
 * Micro **mains libres** de l'écran verrouillé.
 *
 * Au premier plan, la tenue du doigt ouvre le micro et le relâchement l'envoie
 * (`IseeViewModel.onHoldStart/onHoldEnd`). Écran verrouillé il n'y a plus de doigt, plus de
 * surface, plus de caméra : l'unique geste est l'action d'une notification. Cet objet dit donc
 * les trois choses qui restent — **quand envoyer** (un plafond, puisque aucune relâche ne
 * viendra), **ce qu'on affiche** et **ce qu'on prononce** — sans une ligne d'Android, pour que
 * tout soit jouable en JVM.
 */
enum class LockedMicPhase {
    /** Service arrêté : aucun micro, aucune notification. */
    OFF,

    /** Service armé, micro fermé : l'action peut ouvrir le micro. */
    READY,

    /** Micro ouvert : la personne parle. */
    LISTENING,

    /** Micro fermé, transcription (puis réponse) en cours. */
    THINKING,

    /** Réponse obtenue : elle est prononcée et affichée. */
    ANSWERED,

    /** Échec : le message exact est affiché et prononcé. */
    FAILED,
}

/** Ce qu'une notification (et TalkBack) montrent pour une phase donnée. */
data class LockedMicView(
    val title: String,
    val message: String,
    /** Libellé du bouton d'action ; `null` = pas de bouton (rien à faire). */
    val action: String?,
    /** Texte prononcé à voix haute ; `null` = silence (l'écran suffit). */
    val spoken: String?,
)

/** Tout l'état du micro mains libres, immuable : chaque geste rend un nouvel état. */
data class LockedMicState(
    val phase: LockedMicPhase = LockedMicPhase.OFF,
    /** Ce que le moteur a entendu (vide tant que la transcription n'a rien rendu). */
    val transcript: String = "",
    /** Réponse du modèle, telle quelle. */
    val answer: String = "",
    /** Message d'échec affichable (clé absente, réseau, micro refusé…). */
    val error: String = "",
    /** Horodatage du début de capture ; `0` = aucune capture en cours. */
    val listeningSince: Long = 0L,
) {

    /** Service armé : micro fermé, prêt à écouter. */
    fun armed(): LockedMicState = copy(phase = LockedMicPhase.READY, listeningSince = 0L)

    /** Micro ouvert à [nowMs] : l'ancien texte est effacé, rien n'est encore transcrit. */
    fun listen(nowMs: Long): LockedMicState = LockedMicState(
        phase = LockedMicPhase.LISTENING,
        listeningSince = nowMs,
    )

    /** Le micro est fermé et le texte part au moteur. */
    fun transcribing(): LockedMicState = copy(phase = LockedMicPhase.THINKING, listeningSince = 0L)

    /** Réponse obtenue : elle reste affichée et prononcée. */
    fun answered(text: String): LockedMicState = copy(
        phase = LockedMicPhase.ANSWERED,
        answer = text,
        error = "",
        listeningSince = 0L,
    )

    /** Échec : la phase garde le message exact, jamais un « erreur » générique. */
    fun failed(message: String): LockedMicState = copy(
        phase = LockedMicPhase.FAILED,
        error = message,
        listeningSince = 0L,
    )

    /** Arrêt du service : tout est remis à zéro, y compris la dernière réponse. */
    fun off(): LockedMicState = LockedMicState()

    /** Vrai quand la capture en cours a atteint le plafond et doit partir seule. */
    fun shouldSend(nowMs: Long): Boolean =
        phase == LockedMicPhase.LISTENING && listeningSince > 0L &&
            nowMs - listeningSince >= LockedMic.MAX_RECORD_MS

    /** Durée déjà capturée, en millisecondes (`0` hors capture). */
    fun elapsedMs(nowMs: Long): Long =
        if (phase == LockedMicPhase.LISTENING && listeningSince > 0L) {
            (nowMs - listeningSince).coerceAtLeast(0L)
        } else {
            0L
        }

    /** Le texte exact de la notification et de l'annonce, pour cette phase. */
    fun view(): LockedMicView = when (phase) {
        LockedMicPhase.OFF -> LockedMicView(LockedMic.TITLE, "Micro arrêté.", null, null)
        LockedMicPhase.READY -> LockedMicView(
            title = LockedMic.TITLE,
            message = "Touche la notification pour parler, même écran verrouillé.",
            action = LockedMic.ACTION_LISTEN,
            spoken = null,
        )
        LockedMicPhase.LISTENING -> LockedMicView(
            title = LockedMic.TITLE_LISTENING,
            message = "Parle, puis touche la notification pour « ${LockedMic.ACTION_SEND} ».",
            action = LockedMic.ACTION_SEND,
            spoken = "J'écoute",
        )
        LockedMicPhase.THINKING -> LockedMicView(
            title = LockedMic.TITLE_THINKING,
            message = transcript.ifBlank { "Un instant…" }.shortened(),
            action = null,
            spoken = null,
        )
        LockedMicPhase.ANSWERED -> LockedMicView(
            title = LockedMic.TITLE_ANSWER,
            message = answer.ifBlank { "réponse vide" }.shortened(),
            action = LockedMic.ACTION_LISTEN,
            spoken = answer,
        )
        LockedMicPhase.FAILED -> LockedMicView(
            title = LockedMic.TITLE_FAILED,
            message = error.ifBlank { "échec sans message" }.shortened(),
            action = LockedMic.ACTION_LISTEN,
            spoken = "Échec. ${error.ifBlank { "échec sans message" }}",
        )
    }

    /** Une notification ne défile pas : au-delà, le texte est coupé proprement. */
    private fun String.shortened(): String =
        if (length <= LockedMic.MAX_MESSAGE_CHARS) this else take(LockedMic.MAX_MESSAGE_CHARS - 1) + "…"
}

/** Les constantes et libellés du micro mains libres, en un seul endroit. */
object LockedMic {

    /** Étiquette des traces : `adb logcat -s LockedMic`. */
    const val TAG = "LockedMic"

    /** Plafond de capture : personne ne relâchera le doigt, donc on borne la mémoire. */
    const val MAX_RECORD_MS = 20_000L

    /** En dessous, le moteur refuserait l'audio : même seuil que la dictée du chat. */
    const val MIN_RECORD_MS = DictationUseCase.MIN_DURATION_MS

    /** Longueur maximale d'une ligne de notification (au-delà, le texte est coupé). */
    const val MAX_MESSAGE_CHARS = 160

    const val TITLE = "iSee — micro"
    const val TITLE_LISTENING = "iSee — j'écoute"
    const val TITLE_THINKING = "iSee — transcription"
    const val TITLE_ANSWER = "iSee — réponse"
    const val TITLE_FAILED = "iSee — échec"

    const val ACTION_LISTEN = "Écouter"
    const val ACTION_SEND = "Envoyer"
}