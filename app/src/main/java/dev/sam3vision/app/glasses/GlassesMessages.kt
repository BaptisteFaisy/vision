package dev.sam3vision.app.glasses

/**
 * Tous les textes des lunettes, écrits une seule fois, avec leur gravité. Aucun d'eux ne
 * contient de clé ni de token : les erreurs du SDK sont résumées par leur description.
 */
object GlassesMessages {

    fun sdkUnavailable(reason: String) = GlassesMessage(MessageKind.ERROR, "Glasses SDK unavailable: $reason")

    fun registrationFailed(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Registration refused: $reason")

    fun sessionAlreadyRunning() =
        GlassesMessage(MessageKind.WARNING, "A glasses session is already running: stop it first.")

    /** L'attente du lien : c'est l'app qui patiente, l'utilisateur n'a rien à corriger. */
    fun waitingForLink() = GlassesMessage(
        MessageKind.INFO,
        "Glasses not linked yet: waiting for Bluetooth (up to 30 s), keep them unfolded and worn.",
    )

    /**
     * Le lien n'a jamais monté dans le délai. Le geste est sur les lunettes (dépliées, portées),
     * pas un ré-appairage : c'est l'ancien message qui envoyait l'utilisateur sur une fausse piste.
     */
    fun noLinkedDevice() = GlassesMessage(
        MessageKind.ERROR,
        "Glasses not connected yet: unfold them, put them on your nose, check Bluetooth, then restart the session.",
    )

    /** La session meurt avant de servir : l'app réessaie seule, l'utilisateur patiente. */
    fun sessionRetrying(attempt: Int, max: Int) = GlassesMessage(
        MessageKind.INFO,
        "Glasses cut the session instantly, retrying ($attempt/$max)…",
    )

    /** Refus en boucle : le geste qui répare est sur les lunettes, pas dans l'app. */
    fun sessionRefusedByGlasses() = GlassesMessage(
        MessageKind.ERROR,
        "The glasses keep refusing: force-restart them (worn, hold power + capture), then start again.",
    )

    fun noCamera() = GlassesMessage(
        MessageKind.WARNING,
        "Glasses camera inactive: start the session, or allow the camera in the Meta AI app.",
    )

    fun cameraPermissionMissing() = GlassesMessage(MessageKind.ERROR, permissionPath("Camera not allowed"))

    fun cameraUnavailable(reason: String) = GlassesMessage(MessageKind.ERROR, "Camera unavailable: $reason")

    fun frameRateFallback(asked: Int, held: Int) = GlassesMessage(
        MessageKind.WARNING,
        "Glasses link refused $asked fps: the stream holds $held fps instead.",
    )

    fun headPoseUnavailable() = GlassesMessage(
        MessageKind.WARNING,
        "Head pose unavailable: glasses distances run pinhole-only.",
    )

    /**
     * Orientation nulle persistante (paire gen1 / sans fusion) : sténopé seul
     * durable, dit une fois — distinct du refus d'attache [headPoseUnavailable].
     */
    fun headPoseNoFusion() = GlassesMessage(
        MessageKind.WARNING,
        "Head pose has no fusion (gen1 ?): glasses distances run pinhole-only.",
    )

    fun micPermissionMissing() = GlassesMessage(MessageKind.ERROR, permissionPath("Microphone not allowed"))

    fun audioRefused(hertz: Int, reason: String) = GlassesMessage(
        MessageKind.WARNING,
        "Glasses mic refused at ${hertz / 1000} kHz ($reason): falling back to the next format.",
    )

    fun audioUnavailable() = GlassesMessage(
        MessageKind.WARNING,
        "Glasses mic unavailable: the session continues camera-only, " +
            "dictation keeps the phone mic.",
    )

    fun streamStopped() =
        GlassesMessage(MessageKind.WARNING, "Camera stream stopped: restart the session to take a photo again.")

    fun photoUndecodable() = GlassesMessage(
        MessageKind.ERROR,
        "Glasses photo unreadable: format not decoded by the app.",
    )

    fun inputsUnavailable(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Capture button unavailable: $reason")

    fun liveNotOnGlasses() = GlassesMessage(
        MessageKind.INFO,
        "Live mode: not on glasses yet, it stays on the phone.",
    )

    fun registrationStarted() = GlassesMessage(
        MessageKind.INFO,
        "The Meta AI app asks for confirmation: accept, then come back here.",
    )

    fun registrationConfirmed() = GlassesMessage(
        MessageKind.INFO,
        "Glasses registered: you can start the session.",
    )

    fun noActivity() = GlassesMessage(MessageKind.ERROR, "Come back to the app, then restart the registration.")

    /**
     * L'enregistrement a existé puis a disparu : le SDK ne le dit pas, et l'écran se
     * contenterait d'afficher « non enregistrée » sans cause. Cas réel : mise à jour de
     * l'app (chaque build master) ou de Meta AI, réinstallation, `pm clear`.
     */
    fun registrationLost() = GlassesMessage(
        MessageKind.WARNING,
        "Meta AI registration lost (update or reinstall): " +
            "tap “Register with Meta AI”.",
    )

    fun mockUnavailable() =
        GlassesMessage(MessageKind.ERROR, "Mock device unavailable on this phone.")

    fun mockReady() =
        GlassesMessage(MessageKind.INFO, "Mock glasses ready: start the session, then simulate a press.")

    fun noMockPress() = GlassesMessage(MessageKind.WARNING, "No active mock device.")

    /**
     * L'écoute mains-libres est ouverte : ce qui se dit part en question, sans
     * photo. Le téléphone peut rester en poche.
     */
    fun talkListening() = GlassesMessage(
        MessageKind.INFO,
        "Listening (glasses): speak, then long-press again to send (auto-send at 20 s).",
    )

    /**
     * Micro des lunettes inutilisable : dit comme un repli, pas comme un
     * silence. Aucune photo n'est prise.
     */
    fun talkMicUnavailable() = GlassesMessage(
        MessageKind.WARNING,
        "Glasses mic unavailable: long-press on the phone to dictate.",
    )

    fun talkFailed(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Glasses question: $reason")

    fun voiceLaunchStarted() = GlassesMessage(
        MessageKind.INFO,
        "“Hey Meta, open iSee”: connecting, then listening.",
    )

    fun voiceLaunchListening() = GlassesMessage(
        MessageKind.INFO,
        "“Hey Meta, open iSee”: listening, speak.",
    )

    fun voiceLaunchNeedsRegistration() = GlassesMessage(
        MessageKind.ERROR,
        "Voice command received but app not registered: tap “Register with Meta AI”.",
    )

    fun voiceInvocationsUnavailable() = GlassesMessage(
        MessageKind.WARNING,
        "Voice invocations unavailable: long press remains the only trigger.",
    )

    fun voiceInvocationError(reason: String) =
        GlassesMessage(MessageKind.WARNING, "Voice invocation: $reason")

    fun voiceInvocationUnknown() = GlassesMessage(
        MessageKind.WARNING,
        "Unknown voice invocation: ignored (only launch is handled).",
    )

    /**
     * Collecte des frames vidéo active : les descriptions suivent la sortie de la
     * voix ([GlassesVoiceRoute]), donc les lunettes quand la session est ouverte,
     * le haut-parleur en repli (paire hors Bluetooth, sortie refusée).
     */
    fun framesVoiceRoute() = GlassesMessage(
        MessageKind.INFO,
        "Glasses live: description voice follows the glasses, speaker as fallback.",
    )

    fun videoUndecodable() = GlassesMessage(
        MessageKind.WARNING,
        "Glasses video unreadable: this format is not decoded, photo only.",
    )

    /** Vidéo des lunettes publiée dans la galerie : le chemin dit où la retrouver. */
    fun recordingSaved(name: String, seconds: Long, withSound: Boolean) = GlassesMessage(
        MessageKind.INFO,
        "Glasses video saved" + (if (withSound) " with sound" else " (silent)") +
            ": gallery → Movies/iSee/$name ($seconds s).",
    )

    fun recordingEmpty() = GlassesMessage(
        MessageKind.WARNING,
        "No video recorded: the glasses stream delivered no compressed image.",
    )

    fun recordingFailed(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Glasses video not recorded: $reason")

    fun mockConnectTimeout() = GlassesMessage(
        MessageKind.ERROR,
        "Mock glasses: registration too slow, restart the connection.",
    )

    /** Ordre vocal « iSee » : la session demandée s'ouvre, le lien est là. */
    fun toggleStarted() = GlassesMessage(
        MessageKind.INFO,
        "Glasses on.",
    )

    /** Ordre vocal « iSee » : la session en cours s'arrête. */
    fun toggleStopped() = GlassesMessage(
        MessageKind.INFO,
        "Glasses off.",
    )

    /** Ordre vocal « iSee » sans paire liée : refusé, dit par iSee à voix haute. */
    fun toggleNotLinked() = GlassesMessage(
        MessageKind.WARNING,
        "No glasses.",
    )
}
