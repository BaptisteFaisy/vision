package dev.sam3vision.app.glasses

/**
 * Parler aux lunettes sans toucher le téléphone : l'équivalent mains-libres du
 * « tap maintenu » du téléphone (`IseeRootLayout` + `IseeViewModel.onHoldStart`).
 *
 * Le bouton capture n'envoie que des appuis discrets (`SHORT_PRESS`, `HOLD`,
 * `DOUBLE_PRESS`) : il n'y a pas d'événement de relâchement, donc pas de vrai
 * « maintenu puis relâché ». L'équivalent retenu est une **bascule** :
 * premier appui long = le micro des lunettes s'ouvre, second appui long = la
 * question part aussitôt, sinon **envoi automatique à 20 s** (même plafond que
 * le micro verrouillé, qui a le même problème : personne ne relâche le doigt).
 *
 * Tout est pur ici (phases, transitions, plafond) : `GlassesTalk` ne fait
 * qu'exécuter ces décisions avec le micro et le réseau.
 */
enum class GlassesTalkPhase {
    /** Personne ne parle : un appui long ouvre le micro des lunettes. */
    IDLE,

    /** Le micro des lunettes capte : un appui long envoie, le délai aussi. */
    LISTENING,

    /** Micro coupé, transcription en cours : les appuis sont ignorés. */
    SENDING,
}

/** Ce qu'un appui long a décidé, une fois la phase et le micro connus. */
enum class TalkPress {
    /** L'écoute démarre (premier appui long, micro ouvert). */
    STARTED,

    /** L'envoi part (second appui long, ou fin du délai). */
    SENDING,

    /** Le micro des lunettes est indisponible : dit par l'appelant, sans photo. */
    MIC_UNAVAILABLE,

    /** Transcription déjà en cours : l'appui est ignoré, sans bruit. */
    IGNORED,
}

/** Petit signal sonore dans les lunettes : l'écran du téléphone est en poche. */
enum class GlassesTalkCue {
    /** L'écoute commence : parler maintenant. */
    LISTENING,

    /** La question part en transcription. */
    SENT,
}

object GlassesTalkPolicy {

    /**
     * Écoute maximale d'une question (20 s, comme `LockedMic.MAX_RECORD_MS`) :
     * personne ne relâche le doigt, donc le plafond tient lieu de relâchement.
     */
    const val MAX_LISTEN_MS = 20_000L

    /**
     * Transition d'un appui long, micro supposé disponible : `IDLE` ouvre
     * l'écoute, `LISTENING` envoie, `SENDING` ignore. Le micro réellement
     * indisponible rend [TalkPress.MIC_UNAVAILABLE] à la place de `STARTED`.
     */
    fun press(phase: GlassesTalkPhase, micReady: Boolean): TalkPress = when (phase) {
        GlassesTalkPhase.IDLE -> if (micReady) TalkPress.STARTED else TalkPress.MIC_UNAVAILABLE
        GlassesTalkPhase.LISTENING -> TalkPress.SENDING
        GlassesTalkPhase.SENDING -> TalkPress.IGNORED
    }

    /** Fin du délai : n'envoie que si l'écoute est toujours en cours. */
    fun timeout(phase: GlassesTalkPhase): Boolean = phase == GlassesTalkPhase.LISTENING

    /**
     * Ouverture auto à la session : l'écoute ne s'ouvre que si personne ne
     * parle (`IDLE`) et que le micro est prêt. Hors `IDLE`, ne jamais envoyer
     * seule : l'envoi reste un geste (second appui long) ou le délai.
     */
    fun autoListen(phase: GlassesTalkPhase, micReady: Boolean): Boolean =
        phase == GlassesTalkPhase.IDLE && micReady
}
