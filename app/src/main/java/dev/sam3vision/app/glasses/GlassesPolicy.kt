package dev.sam3vision.app.glasses

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
 * Où se donne vraiment une permission des lunettes : **par paquet**, dans Meta AI, sur la page
 * de l'app. Mesuré le 27/10/2026 : en Developer Mode l'entrée vit sous « Applications du mode
 * développeur(se) » — et cette page n'expose alors que « Déconnecter », sans réglage caméra.
 */
internal fun permissionPath(refusal: String): String =
    "$refusal : Meta AI → ☰ → Lunettes → la paire → Paramètres → Applications → " +
        "« Applications du mode développeur(se) » → cette app → Toujours autoriser " +
        "(cette page peut manquer en Developer Mode)."

object GlassesMessages {

    fun sdkUnavailable(reason: String) = GlassesMessage(MessageKind.ERROR, "SDK lunettes indisponible : $reason")

    fun registrationFailed(reason: String) =
        GlassesMessage(MessageKind.ERROR, "Enregistrement refusé : $reason")

    fun sessionRefused(reason: String) = GlassesMessage(MessageKind.ERROR, "Session refusée : $reason")

    fun sessionInterrupted(reason: String) = GlassesMessage(MessageKind.ERROR, "Session interrompue : $reason")

    fun sessionAlreadyRunning() =
        GlassesMessage(MessageKind.WARNING, "Une session lunettes tourne déjà : arrête-la d'abord.")

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
     * Collecte des frames vidéo active : les descriptions partent sur le
     * haut-parleur du téléphone (le routage vers les lunettes reste au banc).
     */
    fun framesOnSpeaker() = GlassesMessage(
        MessageKind.INFO,
        "Direct lunettes : les descriptions sont lues sur le haut-parleur.",
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
 * Libellé du bouton de session : dit l'étape manquante plutôt que de rester grisé sans
 * raison visible. Quand tout est prêt, c'est le libellé d'action habituel.
 */
fun connectLabel(state: GlassesUiState): String = when {
    state.connected -> "Arrêter la session"
    state.phase == SessionPhase.STARTING -> "Session en cours de démarrage…"
    !state.registered -> "Enregistre l'app d'abord"
    else -> "Démarrer la session"
}