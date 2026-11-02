package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionError
import com.meta.wearable.dat.core.registration.RegistrationRequestError
import com.meta.wearable.dat.core.types.RegistrationError

/**
 * Politique des lunettes Ray-Ban Meta : tout ce qui se décide ici est pur — pas d'Android,
 * pas de SDK DAT — donc testable en JVM. Les wrappers plateforme (`GlassesController`,
 * `GlassesCamera`, `GlassesInputs`) ne font que traduire le SDK vers ces règles.
 *
 * Règle de voix : **aucun échec ne reste silencieux**. Chaque message porte sa gravité,
 * et tout échec est au moins [MessageKind.ERROR] : l'écran le teinte et rien n'est perdu
 * si l'utilisateur n'entend pas l'annonce.
 *
 * La logique testable des *appuis* vit déjà dans `:core` (`CaptureGestureBuffer`) ; on ne
 * la déplace pas. La politique de session, elle, reste dans `:app` : elle lit
 * `GlassesUiState`, qui n'existe que là, et une autre mission tient `:core`.
 */
enum class MessageKind {
    /** Conseils, confirmations. */
    INFO,

    /** Dégradation qui n'empêche pas la session de vivre. */
    WARNING,

    /** La fonction demandée ne peut pas aboutir : l'utilisateur doit agir. */
    ERROR,
}

/** Un message destiné à l'utilisateur : le texte et sa gravité ne se séparent pas. */
data class GlassesMessage(val kind: MessageKind, val text: String)

/**
 * Où se donne une permission des lunettes : **par paquet**, et la question se pose dans Meta AI.
 * Le SDK sait l'ouvrir lui-même (`Wearables.RequestPermissionContract`, bouton « Autoriser … »),
 * le chemin manuel n'est que le repli — en Developer Mode la fiche de l'app n'expose que
 * « Déconnecter » (mesuré le 27/10/2026).
 */
internal fun permissionPath(refusal: String): String =
    "$refusal: tap “Allow … (Meta AI)”, Meta AI asks the question. " +
        "Otherwise Meta AI → ☰ → Glasses → the pair → Settings → Apps → " +
        "developer mode apps → this app."

/**
 * Les huit refus de flux du SDK, chacun avec sa cause réelle : « erreur du flux » n'aide pas,
 * « branches refermées » ou « lunettes chaudes » si.
 */
fun streamErrorMessage(error: StreamError): GlassesMessage = when (error) {
    StreamError.STREAM_ERROR ->
        GlassesMessage(MessageKind.ERROR, "Glasses stream: stream error, restart the session.")
    StreamError.CRITICAL_STREAM_ERROR ->
        GlassesMessage(MessageKind.ERROR, "Glasses stream interrupted: restart the session.")
    StreamError.HINGE_CLOSED ->
        GlassesMessage(MessageKind.WARNING, "Glasses arms folded: unfold them to film.")
    StreamError.PERMISSIONS_DENIED -> GlassesMessage(MessageKind.ERROR, permissionPath("Camera refused"))
    StreamError.THERMAL_HOT ->
        GlassesMessage(MessageKind.WARNING, "Glasses hot: the stream will resume once cooled.")
    StreamError.BATTERY_LOW ->
        GlassesMessage(MessageKind.ERROR, "Glasses battery too low to film: recharge them.")
    StreamError.PEAK_POWER_LIMIT ->
        GlassesMessage(MessageKind.ERROR, "The glasses cut the stream to save battery.")
    StreamError.TIMEOUT ->
        GlassesMessage(MessageKind.WARNING, "Glasses stream: no timely answer, restart the session.")
}

/** Les quatre refus de photo du SDK (`CaptureError`). */
fun captureErrorMessage(error: CaptureError): GlassesMessage = when (error) {
    CaptureError.CaptureFailed ->
        GlassesMessage(MessageKind.ERROR, "Glasses photo: shot failed, try again.")
    CaptureError.CaptureInProgress ->
        GlassesMessage(MessageKind.WARNING, "Photo already running: wait for it to finish before pressing again.")
    CaptureError.DeviceDisconnected ->
        GlassesMessage(MessageKind.ERROR, "Glasses disconnected during the photo: restart the session.")
    CaptureError.NotStreaming ->
        GlassesMessage(MessageKind.ERROR, "Stream stopped: the photo needs it, restart the session.")
}

/**
 * Les cinq refus du retour d'enregistrement (`RegistrationRequestError`), quand Meta AI revient
 * dans l'app avec une demande qui n'a pas pu être poursuivie.
 */
fun registrationRequestErrorMessage(error: RegistrationRequestError): GlassesMessage = when (error) {
    RegistrationRequestError.INVALID_REQUEST -> GlassesMessage(
        MessageKind.ERROR,
        "Registration: invalid request, restart it from Settings → Glasses.",
    )
    RegistrationRequestError.ALREADY_HANDLED ->
        GlassesMessage(MessageKind.INFO, "Registration already handled by Meta AI.")
    RegistrationRequestError.CONFIGURATION_INVALID -> GlassesMessage(
        MessageKind.ERROR,
        "Registration: app credentials refused by Meta AI (APPLICATION_ID / CLIENT_TOKEN).",
    )
    RegistrationRequestError.REGISTRATION_FAILED -> GlassesMessage(
        MessageKind.ERROR,
        "Meta AI refused the registration: try again from Settings → Glasses.",
    )
    RegistrationRequestError.CANCELLATION_FAILED -> GlassesMessage(
        MessageKind.ERROR,
        "Cannot cancel the registration: finish it in Meta AI.",
    )
}

/** Nom lisible d'une capacité, pour composer les messages sans jargon du SDK. */
fun permissionLabel(permission: Permission): String = when (permission) {
    Permission.CAMERA -> "glasses camera"
    Permission.MICROPHONE -> "glasses microphone"
}

fun permissionRequested(permission: Permission) = GlassesMessage(
    MessageKind.INFO,
    "Meta AI asks for permission: ${permissionLabel(permission)}. Accept, then come back here.",
)

fun permissionGranted(permission: Permission) = GlassesMessage(
    MessageKind.INFO,
    "Permission granted: ${permissionLabel(permission)}.",
)

fun permissionRefused(permission: Permission) =
    GlassesMessage(MessageKind.ERROR, permissionPath("Permission refused: ${permissionLabel(permission)}"))

/**
 * La page de Meta AI ne s'est pas ouverte, ou la demande a échoué : chaque cause du SDK a sa
 * phrase, parce que « erreur interne » ne dit pas quoi faire.
 */
fun permissionFailed(permission: Permission, error: PermissionError) = GlassesMessage(
    MessageKind.ERROR,
    "${permissionLabel(permission)} : ${permissionErrorReason(error)}",
)

private fun permissionErrorReason(error: PermissionError): String = when (error) {
    PermissionError.META_AI_NOT_INSTALLED ->
        "the Meta AI app is missing from the phone, and it owns the permission."
    PermissionError.NO_DEVICE ->
        "no known pair: pair the glasses in Meta AI and register the app first."
    PermissionError.NO_DEVICE_WITH_CONNECTION ->
        "no connected pair: turn the glasses on, Bluetooth active, then try again."
    PermissionError.CONNECTION_ERROR -> "the connection to the Meta AI app was interrupted, try again."
    PermissionError.REQUEST_IN_PROGRESS -> "a request is already open in Meta AI, answer it."
    PermissionError.REQUEST_TIMEOUT -> "Meta AI did not answer in time, try again."
    PermissionError.INTERNAL_ERROR -> "internal SDK error, try again then restart Meta AI."
}

/**
 * Traduction **erreur par erreur** des refus et fins de session du SDK (les 18 valeurs de
 * `DeviceSessionError`) : chacune dit quoi faire, avec nos mots plutôt que la description brute.
 */
fun sessionErrorMessage(error: DeviceSessionError): GlassesMessage = when (error) {
    DeviceSessionError.CAPABILITY_DENIED ->
        GlassesMessage(MessageKind.ERROR, "Camera refused by the SDK: allow it, then restart the session.")
    DeviceSessionError.NO_ELIGIBLE_DEVICE ->
        GlassesMessage(MessageKind.ERROR, "No pair available: unfold the glasses, wear them to wake the link, then restart the session.")
    DeviceSessionError.SESSION_ALREADY_STOPPED ->
        GlassesMessage(MessageKind.INFO, "Session already stopped on the SDK side: restart it.")
    DeviceSessionError.SESSION_IDLE ->
        GlassesMessage(MessageKind.WARNING, "Session idle: start it to film.")
    DeviceSessionError.CAPABILITY_ALREADY_ADDED ->
        GlassesMessage(MessageKind.INFO, "Camera already attached to the session.")
    DeviceSessionError.CAPABILITY_NOT_FOUND ->
        GlassesMessage(MessageKind.WARNING, "Camera already detached from the session.")
    DeviceSessionError.DEVICE_DISCONNECTED ->
        GlassesMessage(MessageKind.ERROR, "Glasses disconnected: check Bluetooth, then restart the session.")
    DeviceSessionError.SESSION_ENDED_BY_DEVICE ->
        GlassesMessage(MessageKind.ERROR, "The glasses closed the session: restart it.")
    DeviceSessionError.SESSION_ALREADY_EXISTS ->
        GlassesMessage(MessageKind.ERROR, "An SDK session already exists: stop it, then restart.")
    DeviceSessionError.THERMAL_CRITICAL ->
        GlassesMessage(MessageKind.WARNING, "Glasses too hot: let them cool down before resuming.")
    DeviceSessionError.THERMAL_EMERGENCY ->
        GlassesMessage(MessageKind.ERROR, "Glasses overheating: session cut, let them cool down.")
    DeviceSessionError.PEAK_POWER_SHUTDOWN ->
        GlassesMessage(MessageKind.ERROR, "Power spike: the glasses cut the session.")
    DeviceSessionError.BATTERY_CRITICAL ->
        GlassesMessage(MessageKind.ERROR, "Glasses battery critical: recharge them.")
    DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
        GlassesMessage(MessageKind.ERROR, "The glasses DAT app must be updated: the Meta AI page opens.")
    DeviceSessionError.DWA_UNAVAILABLE ->
        GlassesMessage(MessageKind.ERROR, "Glasses service unavailable: update the Meta AI app, then try again.")
    DeviceSessionError.INSUFFICIENT_SDK_VERSION ->
        GlassesMessage(MessageKind.ERROR, "SDK too old for these glasses: update the Meta AI app and the DAT app.")
    DeviceSessionError.DWA_OUT_OF_STU_RANGE ->
        GlassesMessage(MessageKind.ERROR, "Glasses out of update range: bring them closer to the phone.")
    DeviceSessionError.UNEXPECTED_ERROR ->
        GlassesMessage(MessageKind.ERROR, "Unexpected SDK error: restart the session, then the app.")
}

/** Deux erreurs se réparent sur la page de mise à jour de l'app DAT des lunettes, dans Meta AI. */
fun sessionErrorNeedsGlassesAppUpdate(error: DeviceSessionError): Boolean =
    error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ||
        error == DeviceSessionError.INSUFFICIENT_SDK_VERSION

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

/**
 * Pourquoi la collecte des frames vidéo ne tourne pas — `null` quand elle tourne.
 *
 * La collecte éteinte est un **silence documenté** : pas de frames, donc pas
 * d'analyse ni de voix, et cette phrase en est la raison écrite (testée en JVM).
 * `enabled` est le drapeau de `GlassesCamera`, `hasConsumer` dit si quelqu'un
 * consomme les frames (sans consommateur, collecter paierait le décodage pour rien).
 */
fun frameCollectionIdleReason(enabled: Boolean, hasConsumer: Boolean): String? = when {
    !enabled -> "Glasses frame collection disabled: the stream is not read."
    !hasConsumer -> "Glasses frame collection without consumer: the stream is not read."
    else -> null
}

/**
 * Deux « erreurs » d'enregistrement n'en sont pas : le SDK les émet quand l'état demandé est
 * déjà le sien. Les annoncer en rouge serait un faux positif — on ne dit que le reste.
 */
fun registrationErrorWorthTelling(error: RegistrationError): Boolean =
    error != RegistrationError.ALREADY_REGISTERED && error != RegistrationError.ALREADY_UNREGISTERED

/**
 * Écho `UNEXPECTED_ERROR` post-`STOPPED` : le SDK en émet un 1-3 ms après la fin causale,
 * il ne doit pas écraser le message causal affiché. Miroir de [registrationErrorWorthTelling].
 */
fun sessionErrorWorthTelling(error: DeviceSessionError, phase: SessionPhase, current: GlassesMessage?): Boolean = !(error == DeviceSessionError.UNEXPECTED_ERROR && phase == SessionPhase.STOPPED && current?.kind == MessageKind.ERROR)

/** Les messages d'enregistrement vivent dans [GlassesRegistrationPolicy] (garde 500 lignes). */

/** Une session terminée n'est pas une session : il faut pouvoir en rouvrir une. */
fun SessionPhase.isFinished(): Boolean = this == SessionPhase.STOPPED

/** Phase de session en anglais, pour la ligne d'état (pas de nom d'enum à l'écran). */
fun SessionPhase.label(): String = when (this) {
    SessionPhase.IDLE -> "stopped"
    SessionPhase.STARTING -> "connecting"
    SessionPhase.STARTED -> "running"
    SessionPhase.STOPPED -> "ended"
}

/**
 * Le SDK n'ouvre une session que hors démarrage et hors session active. Une session
 * `STOPPED` (paire retirée, Bluetooth perdu) redevient donc rejoignable : sans cela le
 * bouton restait grisé pour toujours et l'utilisateur n'avait plus aucun moyen d'agir.
 */
fun SessionPhase.allowsConnect(): Boolean = this == SessionPhase.IDLE || isFinished()

/**
 * Pourquoi la photo ne peut pas aboutir, en une ligne courte — `null` quand tout est prêt.
 * Affichée sous les boutons : un bouton grisé sans explication ne dit rien à personne.
 * Le bouton, lui, garde exactement son activation d'avant (`connected`) : on n'ajoute
 * aucune règle qui pourrait griser un chemin qui marche sur du vrai matériel.
 */
fun photoHint(state: GlassesUiState): String? = when {
    !state.connected -> "Photo: start the session first."
    !state.cameraAllowed -> "Photo: camera not allowed in the Meta AI app."
    !state.streaming -> "Photo: camera stream inactive."
    else -> null
}

/**
 * Indication d'écoute mains-libres — `null` hors écoute. Affichée sous les
 * boutons avec `photoHint` : l'utilisateur sait que le micro des lunettes
 * capte, et comment envoyer.
 */
fun talkHint(state: GlassesUiState): String? =
    if (state.talkListening) "Speak, then long-press again (or wait 20 s)." else null

/**
 * Libellé du bouton de session : dit l'étape manquante plutôt que de rester grisé sans
 * raison visible. Quand tout est prêt, c'est le libellé d'action habituel.
 */
fun connectLabel(state: GlassesUiState): String = when {
    state.connected -> "Stop the session"
    state.phase == SessionPhase.STARTING -> "Session starting…"
    !state.registered -> "Register the app first"
    else -> "Start the session"
}
