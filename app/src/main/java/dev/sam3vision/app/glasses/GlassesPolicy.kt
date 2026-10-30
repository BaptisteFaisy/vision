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
 * Tous les textes des lunettes, écrits une seule fois, avec leur gravité. Aucun d'eux ne
 * contient de clé ni de token : les erreurs du SDK sont résumées par leur description.
 */
/**
 * Où se donne une permission des lunettes : **par paquet**, et la question se pose dans Meta AI.
 * Le SDK sait l'ouvrir lui-même (`Wearables.RequestPermissionContract`, bouton « Autoriser … »),
 * le chemin manuel n'est que le repli — en Developer Mode la fiche de l'app n'expose que
 * « Déconnecter » (mesuré le 27/10/2026).
 */
internal fun permissionPath(refusal: String): String =
    "$refusal : touche « Autoriser … (Meta AI) », Meta AI pose la question. " +
        "Sinon Meta AI → ☰ → Lunettes → la paire → Paramètres → Applications → " +
        "applications du mode développeur → cette app."

/** Nom lisible d'une capacité, pour composer les messages sans jargon du SDK. */
/**
 * Les huit refus de flux du SDK, chacun avec sa cause réelle : « erreur du flux » n'aide pas,
 * « branches refermées » ou « lunettes chaudes » si.
 */
fun streamErrorMessage(error: StreamError): GlassesMessage = when (error) {
    StreamError.STREAM_ERROR ->
        GlassesMessage(MessageKind.ERROR, "Flux lunettes : erreur du flux, redémarre la session.")
    StreamError.CRITICAL_STREAM_ERROR ->
        GlassesMessage(MessageKind.ERROR, "Flux lunettes interrompu : redémarre la session.")
    StreamError.HINGE_CLOSED ->
        GlassesMessage(MessageKind.WARNING, "Branches des lunettes refermées : ouvre-les pour filmer.")
    StreamError.PERMISSIONS_DENIED -> GlassesMessage(MessageKind.ERROR, permissionPath("Caméra refusée"))
    StreamError.THERMAL_HOT ->
        GlassesMessage(MessageKind.WARNING, "Lunettes chaudes : le flux reprendra une fois refroidies.")
    StreamError.BATTERY_LOW ->
        GlassesMessage(MessageKind.ERROR, "Batterie des lunettes trop basse pour filmer : recharge-les.")
    StreamError.PEAK_POWER_LIMIT ->
        GlassesMessage(MessageKind.ERROR, "Les lunettes ont coupé le flux pour économiser la batterie.")
    StreamError.TIMEOUT ->
        GlassesMessage(MessageKind.WARNING, "Flux lunettes : pas de réponse à temps, redémarre la session.")
}

/** Les quatre refus de photo du SDK (`CaptureError`). */
fun captureErrorMessage(error: CaptureError): GlassesMessage = when (error) {
    CaptureError.CaptureFailed ->
        GlassesMessage(MessageKind.ERROR, "Photo des lunettes : prise de vue échouée, réessaie.")
    CaptureError.CaptureInProgress ->
        GlassesMessage(MessageKind.WARNING, "Photo déjà en cours : attends la fin avant de rappuyer.")
    CaptureError.DeviceDisconnected ->
        GlassesMessage(MessageKind.ERROR, "Lunettes déconnectées pendant la photo : redémarre la session.")
    CaptureError.NotStreaming ->
        GlassesMessage(MessageKind.ERROR, "Flux arrêté : la photo en a besoin, redémarre la session.")
}

/**
 * Les cinq refus du retour d'enregistrement (`RegistrationRequestError`), quand Meta AI revient
 * dans l'app avec une demande qui n'a pas pu être poursuivie.
 */
fun registrationRequestErrorMessage(error: RegistrationRequestError): GlassesMessage = when (error) {
    RegistrationRequestError.INVALID_REQUEST -> GlassesMessage(
        MessageKind.ERROR,
        "Enregistrement : demande invalide, relance-le depuis Réglages → Lunettes.",
    )
    RegistrationRequestError.ALREADY_HANDLED ->
        GlassesMessage(MessageKind.INFO, "Enregistrement déjà traité par Meta AI.")
    RegistrationRequestError.CONFIGURATION_INVALID -> GlassesMessage(
        MessageKind.ERROR,
        "Enregistrement : identifiants de l'app refusés par Meta AI (APPLICATION_ID / CLIENT_TOKEN).",
    )
    RegistrationRequestError.REGISTRATION_FAILED -> GlassesMessage(
        MessageKind.ERROR,
        "Meta AI a refusé l'enregistrement : réessaie depuis Réglages → Lunettes.",
    )
    RegistrationRequestError.CANCELLATION_FAILED -> GlassesMessage(
        MessageKind.ERROR,
        "Annulation de l'enregistrement impossible : termine-la dans Meta AI.",
    )
}

fun permissionLabel(permission: Permission): String = when (permission) {
    Permission.CAMERA -> "caméra des lunettes"
    Permission.MICROPHONE -> "micro des lunettes"
}

fun permissionRequested(permission: Permission) = GlassesMessage(
    MessageKind.INFO,
    "Meta AI demande l'autorisation : ${permissionLabel(permission)}. Accepte, puis reviens ici.",
)

fun permissionGranted(permission: Permission) = GlassesMessage(
    MessageKind.INFO,
    "Autorisation accordée : ${permissionLabel(permission)}.",
)

fun permissionRefused(permission: Permission) =
    GlassesMessage(MessageKind.ERROR, permissionPath("Autorisation refusée : ${permissionLabel(permission)}"))

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
        "l'app Meta AI est absente du téléphone, et c'est elle qui porte l'autorisation."
    PermissionError.NO_DEVICE ->
        "aucune paire connue : appaire les lunettes dans Meta AI et enregistre l'app d'abord."
    PermissionError.NO_DEVICE_WITH_CONNECTION ->
        "aucune paire connectée : allume les lunettes, Bluetooth actif, puis réessaie."
    PermissionError.CONNECTION_ERROR -> "la connexion à l'app Meta AI a été interrompue, réessaie."
    PermissionError.REQUEST_IN_PROGRESS -> "une demande est déjà ouverte dans Meta AI, réponds-y."
    PermissionError.REQUEST_TIMEOUT -> "Meta AI n'a pas répondu à temps, réessaie."
    PermissionError.INTERNAL_ERROR -> "erreur interne du SDK, réessaie puis redémarre Meta AI."
}

/**
 * Traduction **erreur par erreur** des refus et fins de session du SDK (les 18 valeurs de
 * `DeviceSessionError`) : chacune dit quoi faire, aucune ne laisse la description anglaise nue.
 */
fun sessionErrorMessage(error: DeviceSessionError): GlassesMessage = when (error) {
    DeviceSessionError.CAPABILITY_DENIED ->
        GlassesMessage(MessageKind.ERROR, "Caméra refusée par le SDK : autorise-la, puis redémarre la session.")
    DeviceSessionError.NO_ELIGIBLE_DEVICE ->
        GlassesMessage(MessageKind.ERROR, "Aucune paire disponible : déplie les lunettes, mets-les pour réveiller le lien, puis redémarre la session.")
    DeviceSessionError.SESSION_ALREADY_STOPPED ->
        GlassesMessage(MessageKind.INFO, "Session déjà arrêtée côté SDK : redémarre-la.")
    DeviceSessionError.SESSION_IDLE ->
        GlassesMessage(MessageKind.WARNING, "Session au repos : démarre-la pour filmer.")
    DeviceSessionError.CAPABILITY_ALREADY_ADDED ->
        GlassesMessage(MessageKind.INFO, "Caméra déjà attachée à la session.")
    DeviceSessionError.CAPABILITY_NOT_FOUND ->
        GlassesMessage(MessageKind.WARNING, "Caméra déjà détachée de la session.")
    DeviceSessionError.DEVICE_DISCONNECTED ->
        GlassesMessage(MessageKind.ERROR, "Lunettes déconnectées : vérifie le Bluetooth, puis redémarre la session.")
    DeviceSessionError.SESSION_ENDED_BY_DEVICE ->
        GlassesMessage(MessageKind.ERROR, "Les lunettes ont fermé la session : redémarre-la.")
    DeviceSessionError.SESSION_ALREADY_EXISTS ->
        GlassesMessage(MessageKind.ERROR, "Une session SDK existe déjà : arrête-la, puis redémarre.")
    DeviceSessionError.THERMAL_CRITICAL ->
        GlassesMessage(MessageKind.WARNING, "Lunettes trop chaudes : laisse-les refroidir avant de reprendre.")
    DeviceSessionError.THERMAL_EMERGENCY ->
        GlassesMessage(MessageKind.ERROR, "Surchauffe des lunettes : session coupée, laisse-les refroidir.")
    DeviceSessionError.PEAK_POWER_SHUTDOWN ->
        GlassesMessage(MessageKind.ERROR, "Pic de consommation : les lunettes ont coupé la session.")
    DeviceSessionError.BATTERY_CRITICAL ->
        GlassesMessage(MessageKind.ERROR, "Batterie des lunettes critique : recharge-les.")
    DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
        GlassesMessage(MessageKind.ERROR, "L'app DAT des lunettes doit être mise à jour : la page Meta AI s'ouvre.")
    DeviceSessionError.DWA_UNAVAILABLE ->
        GlassesMessage(MessageKind.ERROR, "Service lunettes indisponible : mets à jour l'app Meta AI, puis réessaie.")
    DeviceSessionError.INSUFFICIENT_SDK_VERSION ->
        GlassesMessage(MessageKind.ERROR, "SDK trop ancien pour ces lunettes : mets à jour l'app Meta AI et l'app DAT.")
    DeviceSessionError.DWA_OUT_OF_STU_RANGE ->
        GlassesMessage(MessageKind.ERROR, "Lunettes hors de portée de mise à jour : rapproche-les du téléphone.")
    DeviceSessionError.UNEXPECTED_ERROR ->
        GlassesMessage(MessageKind.ERROR, "Erreur inattendue du SDK : redémarre la session, puis l'app.")
}

/** Deux erreurs se réparent sur la page de mise à jour de l'app DAT des lunettes, dans Meta AI. */
fun sessionErrorNeedsGlassesAppUpdate(error: DeviceSessionError): Boolean =
    error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ||
        error == DeviceSessionError.INSUFFICIENT_SDK_VERSION

object GlassesMessages {

    fun sdkUnavailable(reason: String) = GlassesMessage(MessageKind.ERROR, "SDK lunettes indisponible : $reason")

    fun registrationFailed(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Enregistrement refusé : $reason")

    fun sessionRefused(reason: String) = GlassesMessage(MessageKind.ERROR, "Session refusée : $reason")

    fun sessionInterrupted(reason: String) = GlassesMessage(MessageKind.ERROR, "Session interrompue : $reason")

    fun sessionAlreadyRunning() =
        GlassesMessage(MessageKind.WARNING, "Une session lunettes tourne déjà : arrête-la d'abord.")

    /** L'attente du lien : c'est l'app qui patiente, l'utilisateur n'a rien à corriger. */
    fun waitingForLink() = GlassesMessage(
        MessageKind.INFO,
        "Lunettes pas encore liées : j'attends le Bluetooth (jusqu'à 30 s), garde-les dépliées et portées.",
    )

    /**
     * Le lien n'a jamais monté dans le délai. Le geste est sur les lunettes (dépliées, portées),
     * pas un ré-appairage : c'est l'ancien message qui envoyait l'utilisateur sur une fausse piste.
     */
    fun noLinkedDevice() = GlassesMessage(
        MessageKind.ERROR,
        "Lunettes pas encore connectées : déplie-les, mets-les sur le nez, vérifie le Bluetooth, puis redémarre la session.",
    )

    fun noCamera() = GlassesMessage(
        MessageKind.WARNING,
        "Caméra lunettes inactive : démarre la session, ou autorise la caméra dans l'app Meta AI.",
    )

    fun cameraPermissionMissing() = GlassesMessage(MessageKind.ERROR, permissionPath("Caméra non autorisée"))

    fun cameraUnavailable(reason: String) = GlassesMessage(MessageKind.ERROR, "Caméra indisponible : $reason")

    fun micPermissionMissing() = GlassesMessage(MessageKind.ERROR, permissionPath("Micro non autorisé"))

    fun audioRefused(hertz: Int, reason: String) = GlassesMessage(
        MessageKind.WARNING,
        "Micro lunettes refusé en ${hertz / 1000} kHz ($reason) : repli sur le format suivant.",
    )

    fun audioUnavailable() = GlassesMessage(
        MessageKind.WARNING,
        "Micro lunettes indisponible : la session continue en caméra seule, " +
            "la dictée garde le micro du téléphone.",
    )

    fun streamRefused(reason: String) = GlassesMessage(MessageKind.ERROR, "Flux refusé : $reason")

    fun streamError(reason: String) = GlassesMessage(MessageKind.WARNING, "Flux lunettes : $reason")

    fun streamStopped() =
        GlassesMessage(MessageKind.WARNING, "Flux caméra arrêté : redémarre la session pour reprendre une photo.")

    fun photoRefused(reason: String) = GlassesMessage(MessageKind.ERROR, "Photo refusée : $reason")

    fun photoUndecodable() = GlassesMessage(
        MessageKind.ERROR,
        "Photo des lunettes illisible : format non décodé par l'app.",
    )

    fun inputsUnavailable(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Bouton capture indisponible : $reason")

    fun liveNotOnGlasses() = GlassesMessage(
        MessageKind.INFO,
        "Mode Direct : pas encore porté sur lunettes, il reste sur le téléphone.",
    )

    fun registrationStarted() = GlassesMessage(
        MessageKind.INFO,
        "L'app Meta AI demande confirmation : accepte, puis reviens ici.",
    )

    fun noActivity() = GlassesMessage(MessageKind.ERROR, "Reviens dans l'app, puis relance l'enregistrement.")

    /**
     * L'enregistrement a existé puis a disparu : le SDK ne le dit pas, et l'écran se
     * contenterait d'afficher « non enregistrée » sans cause. Cas réel : mise à jour de
     * l'app (chaque build master) ou de Meta AI, réinstallation, `pm clear`.
     */
    fun registrationLost() = GlassesMessage(
        MessageKind.WARNING,
        "Enregistrement Meta AI perdu (mise à jour ou réinstallation) : " +
            "touche « Enregistrer auprès de Meta AI ».",
    )

    fun mockUnavailable() =
        GlassesMessage(MessageKind.ERROR, "Appareil simulé indisponible sur ce téléphone.")

    fun mockReady() =
        GlassesMessage(MessageKind.INFO, "Lunettes simulées prêtes : démarre la session, puis simule un appui.")

    fun noMockPress() = GlassesMessage(MessageKind.WARNING, "Aucun appareil simulé actif.")

    /**
     * L'écoute mains-libres est ouverte : ce qui se dit part en question, avec
     * la photo des lunettes. Le téléphone peut rester en poche.
     */
    fun talkListening() = GlassesMessage(
        MessageKind.INFO,
        "J'écoute (lunettes) : parle, puis rappuie longuement pour envoyer (envoi auto à 20 s).",
    )

    /**
     * Micro des lunettes inutilisable : l'ancien comportement reste (photo →
     * chat sur le téléphone), dit comme un repli, pas comme un silence.
     */
    fun talkMicUnavailable() = GlassesMessage(
        MessageKind.WARNING,
        "Micro lunettes indisponible : photo envoyée au chat, comme avant.",
    )

    fun talkFailed(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Question lunettes : $reason")

    fun voiceLaunchStarted() = GlassesMessage(
        MessageKind.INFO,
        "« Hey Meta, ouvre iSee » : connexion, puis j'écoute.",
    )

    fun voiceLaunchListening() = GlassesMessage(
        MessageKind.INFO,
        "« Hey Meta, ouvre iSee » : j'écoute, parle.",
    )

    fun voiceLaunchNeedsRegistration() = GlassesMessage(
        MessageKind.ERROR,
        "Commande vocale reçue mais app non enregistrée : touche « Enregistrer auprès de Meta AI ».",
    )

    fun voiceInvocationsUnavailable() = GlassesMessage(
        MessageKind.WARNING,
        "Invocations vocales indisponibles : l'appui long reste le seul déclencheur.",
    )

    fun voiceInvocationError(reason: String) =
        GlassesMessage(MessageKind.WARNING, "Invocation vocale : $reason")

    fun voiceInvocationUnknown() = GlassesMessage(
        MessageKind.WARNING,
        "Invocation vocale inconnue : ignorée (seul le lancement est géré).",
    )

    /**
     * Collecte des frames vidéo active : les descriptions suivent la sortie de la
     * voix ([GlassesVoiceRoute]), donc les lunettes quand la session est ouverte,
     * le haut-parleur en repli (paire hors Bluetooth, sortie refusée).
     */
    fun framesVoiceRoute() = GlassesMessage(
        MessageKind.INFO,
        "Direct lunettes : la voix des descriptions suit les lunettes, haut-parleur en repli.",
    )

    fun videoUndecodable() = GlassesMessage(
        MessageKind.WARNING,
        "Vidéo des lunettes illisible : ce format n'est pas décodé, photo seule.",
    )

    /** Vidéo des lunettes publiée dans la galerie : le chemin dit où la retrouver. */
    fun recordingSaved(name: String, seconds: Long, withSound: Boolean) = GlassesMessage(
        MessageKind.INFO,
        "Vidéo des lunettes enregistrée" + (if (withSound) " avec le son" else " (sans son)") +
            " : galerie → Movies/iSee/$name ($seconds s).",
    )

    fun recordingEmpty() = GlassesMessage(
        MessageKind.WARNING,
        "Aucune vidéo enregistrée : le flux lunettes n'a livré aucune image compressée.",
    )

    fun recordingFailed(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Vidéo des lunettes non enregistrée : $reason")

    fun mockConnectTimeout() = GlassesMessage(
        MessageKind.ERROR,
        "Lunettes simulées : enregistrement trop lent, relance la connexion.",
    )

    /** Ordre vocal « iSee » : la session demandée s'ouvre, le lien est là. */
    fun toggleStarted() = GlassesMessage(
        MessageKind.INFO,
        "Session lunettes : j'ouvre la session.",
    )

    /** Ordre vocal « iSee » : la session en cours s'arrête. */
    fun toggleStopped() = GlassesMessage(
        MessageKind.INFO,
        "Session lunettes arrêtée.",
    )

    /** Ordre vocal « iSee » sans paire liée : refusé, dit par iSee à voix haute. */
    fun toggleNotLinked() = GlassesMessage(
        MessageKind.WARNING,
        "Lunettes pas connectées en Bluetooth : session non ouverte.",
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
    !enabled -> "Collecte des frames lunettes désactivée : le flux n'est pas lu."
    !hasConsumer -> "Collecte des frames lunettes sans consommateur : le flux n'est pas lu."
    else -> null
}

/**
 * Deux « erreurs » d'enregistrement n'en sont pas : le SDK les émet quand l'état demandé est
 * déjà le sien. Les annoncer en rouge serait un faux positif — on ne dit que le reste.
 */
fun registrationErrorWorthTelling(error: RegistrationError): Boolean =
    error != RegistrationError.ALREADY_REGISTERED && error != RegistrationError.ALREADY_UNREGISTERED

/**
 * Ce que la **perte** d'enregistrement doit dire, et quand se taire : rien au premier état
 * (au démarrage, `wasRegistered` est faux), rien quand c'est l'utilisateur qui s'est
 * désenregistré (`wanted`), sinon l'avertissement. Pur, donc testé en JVM.
 */
fun registrationLostMessage(
    wasRegistered: Boolean,
    registered: Boolean,
    wanted: Boolean,
): GlassesMessage? = when {
    !wasRegistered || registered -> null
    wanted -> null
    else -> GlassesMessages.registrationLost()
}

/** Une session terminée n'est pas une session : il faut pouvoir en rouvrir une. */
fun SessionPhase.isFinished(): Boolean = this == SessionPhase.STOPPED

/** Phase de session en français, pour la ligne d'état (pas de nom d'enum à l'écran). */
fun SessionPhase.labelFr(): String = when (this) {
    SessionPhase.IDLE -> "arrêtée"
    SessionPhase.STARTING -> "démarrage"
    SessionPhase.STARTED -> "en cours"
    SessionPhase.STOPPED -> "terminée"
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
    !state.connected -> "Photo : démarre la session d'abord."
    !state.cameraAllowed -> "Photo : caméra non autorisée côté app Meta AI."
    !state.streaming -> "Photo : flux caméra inactif."
    else -> null
}

/**
 * Indication d'écoute mains-libres — `null` hors écoute. Affichée sous les
 * boutons avec `photoHint` : l'utilisateur sait que le micro des lunettes
 * capte, et comment envoyer.
 */
fun talkHint(state: GlassesUiState): String? =
    if (state.talkListening) "Parle, puis rappuie longuement (ou attends 20 s)." else null

/**
 * Libellé du bouton de session : dit l'étape manquante plutôt que de rester grisé sans
 * raison visible. Quand tout est prêt, c'est le libellé d'action habituel.
 */
fun connectLabel(state: GlassesUiState): String = when {
    state.connected -> "Arrêter la session"
    state.phase == SessionPhase.STARTING -> "Session en cours de démarrage…"
    !state.registered -> "Enregistre l'app d'abord"
    else -> "Démarrer la session"
}
