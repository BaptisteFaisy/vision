package dev.sam3vision.app.glasses.bench

import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.RegistrationState

/**
 * Règles de décision du cycle lunettes, sans Android et sans effet de bord.
 *
 * Ces fonctions ne journalisent rien, n'ouvrent aucune session et ne touchent
 * aucun objet DAT : elles disent seulement quoi faire d'un état ou d'une erreur.
 * Les appelants ([GlassesRegistrationPhase], [GlassesDeviceSession],
 * [GlassesFramePump]) exécutent la décision ; ils ne la prennent pas. C'est cette
 * couche qui est prouvée sur JVM par `SessionPolicyTest`, sans stubber le SDK.
 *
 * L'étranglement et le verdict de frame ont déménagé dans le paquet parent
 * (`glasses/GlassesFramePolicy.kt`), partagés avec le collecteur de production :
 * ce fichier ne garde que le cycle du banc (enregistrement, erreurs, démarrage).
 *
 * Compatible JVM : `RegistrationState` et `StreamError` sont des enums Kotlin
 * (vérifié par `javap` sur les AAR `mwdat-core` / `mwdat-camera` 1.0.1), donc
 * instanciables hors Android ; seule `getLocalizedDescription` exige un `Context`.
 */

/**
 * Verrou « une seule fois par cycle » : garde d'un lancement coûteux (ouverture
 * d'une UI d'enregistrement, création de session, ajout de caméra).
 *
 * Les phases DAT appellent [arm] avant d'agir et s'arrêtent si elle rend `false`.
 * Le verrou est propre au cycle : un nouveau cycle crée de nouveaux verrous, et
 * [disarm] ne sert qu'au seul cas où l'échec autorise une nouvelle tentative
 * (création de session refusée → un prochain état `REGISTERED` réessaiera).
 */
internal class CycleLatch {

    /** Vrai dès qu'une action a été armée ; remis à faux par [disarm]. */
    var isArmed: Boolean = false
        private set

    /** Arme le verrou ; rend `false` s'il l'était déjà (l'appelant doit alors ne rien faire). */
    fun arm(): Boolean {
        if (isArmed) return false
        isArmed = true
        return true
    }

    /** Réarme une nouvelle tentative. Sans effet observable si le verrou était libre. */
    fun disarm() {
        isArmed = false
    }
}

/** Action à mener quand `Wearables.registrationState` émet un nouvel état. */
internal enum class RegistrationStep {
    /** Rien à lancer : la ligne de journal suffit. */
    LOG_ONLY,

    /** Demander l'enregistrement (l'ouverture d'UI est gardée par l'appelant). */
    REQUEST_REGISTRATION,

    /** Ouvrir la session d'appareil. */
    OPEN_SESSION,
}

/** Étape à suivre et ligne de journal exacte pour un état d'enregistrement. */
internal data class RegistrationDecision(val step: RegistrationStep, val message: String)

/**
 * Traduit un état d'enregistrement DAT en (étape, message).
 *
 * `when` exhaustif sur l'enum : un état ajouté par une future version du SDK
 * casse la compilation au lieu de tomber silencieusement dans « ne rien faire ».
 */
internal fun registrationDecision(state: RegistrationState): RegistrationDecision = when (state) {
    RegistrationState.REGISTERED ->
        RegistrationDecision(RegistrationStep.OPEN_SESSION, "glasses registered")

    RegistrationState.AVAILABLE ->
        RegistrationDecision(RegistrationStep.REQUEST_REGISTRATION, "waiting for registration")

    RegistrationState.REGISTERING -> logOnly("registration in progress")
    RegistrationState.UNREGISTERING -> logOnly("unregistration in progress")
    RegistrationState.UNAVAILABLE -> logOnly("no glasses pair available")
}

private fun logOnly(message: String) =
    RegistrationDecision(RegistrationStep.LOG_ONLY, message)

/**
 * Étapes qui quittent le thread de l'émetteur `registrationState` avant d'agir
 * (`true` = exécutées dans un `launchSafe`, pas en ligne dans `emit`).
 *
 * ANR du 27/10/2026 (`data_app_anr` du banc lunettes) : l'émetteur du SDK tourne
 * sur le main thread **en tenant son verrou d'enregistrement**, et
 * `REQUEST_REGISTRATION` appelait `Wearables.startRegistration` en ligne depuis
 * `emit`. Cet appel synchrone prend le même verrou puis attend celui du registre
 * ACDC, tenu par un thread binder qui attend le premier — interblocage AB-BA sur
 * le main, service ACDC jamais exécuté, ANR « executing service
 * …ACDCRegistrationService ». Seule cette étape émet un appel DAT synchrone
 * bloquant depuis le collecteur : `OPEN_SESSION` ne fait que suspendre (`delay`,
 * `io`), ce qui rend la main au SDK au lieu de le bloquer.
 */
internal fun runsOffEmitter(step: RegistrationStep): Boolean = when (step) {
    RegistrationStep.REQUEST_REGISTRATION -> true
    RegistrationStep.OPEN_SESSION, RegistrationStep.LOG_ONLY -> false
}

/** Suite à donner à une erreur du flux vidéo. */
internal enum class StreamErrorDisposition {
    /** Le flux peut repartir seul : on journalise et on continue. */
    LOG_ONLY,

    /** Le flux est mort : on arrête le cycle. */
    STOP_CYCLE,
}

/**
 * Seule l'erreur critique arrête le cycle. Batterie faible, charnière fermée,
 * thermique et délai dépassé sont transitoires côté DAT : couper le cycle
 * pour eux rendrait le spike inutilisable en usage réel.
 */
internal fun streamErrorDisposition(error: StreamError): StreamErrorDisposition =
    if (error == StreamError.CRITICAL_STREAM_ERROR) {
        StreamErrorDisposition.STOP_CYCLE
    } else {
        StreamErrorDisposition.LOG_ONLY
    }

// ---- Démarrage sur de vraies lunettes ----------------------------------------
// La porte « lien `CONNECTED`, sinon rien » vit désormais dans le paquet parent
// (`glasses/GlassesLinkGate.kt`) : l'app l'ouvre aussi avant `createSession`.

/**
 * Ce que le SDK voit au moment où le mode « Lunettes » démarre, réduit à des
 * valeurs brutes (aucun `Context`, aucun appel DAT) : c'est [realStartAdvice] qui
 * décide, et elle se teste sur JVM comme le reste de ce fichier.
 *
 * Le mode réel échoue silencieusement : `AutoDeviceSelector` sur un SDK sans
 * paire ne rend jamais d'erreur, la session reste en attente et l'écran ne dit
 * rien. Ces quatre faits sont exactement ce qui manque à l'utilisateur pour
 * savoir quel geste faire — c'est pourquoi ils sont lus et affichés.
 */
internal data class RealStartFacts(
    /** `Wearables.isDevMode` : sans lui, l'attestation est refusée et rien ne s'enregistre. */
    val devMode: Boolean,
    /** `Wearables.registrationState.value` : l'enregistrement auprès de l'app Meta AI. */
    val registration: RegistrationState,
    /** Nombre d'appareils connus du SDK (`Wearables.devices.value.size`). */
    val devices: Int,
    /** Permission caméra **côté DAT** (`Permission.CAMERA`), pas la permission Android. */
    val cameraAllowed: Boolean,
)

/**
 * Lignes de journal d'un démarrage réel, dans l'ordre où il faut les lire.
 *
 * Vide de sens tant qu'il y a un geste à faire : chaque ligne dit **où cliquer**,
 * en français, et se termine par la ligne « prêt » seulement quand tout est en
 * place. Aucune ligne n'est émise pour un fait déjà satisfait.
 */
internal fun realStartAdvice(facts: RealStartFacts): List<String> {
    val lines = buildList {
        if (!facts.devMode) add(DEV_MODE_HELP)
        registrationHelp(facts.registration)?.let(::add)
        if (facts.devices == 0) add(NO_DEVICE_HELP)
        if (!facts.cameraAllowed) add(CAMERA_HELP)
    }
    return lines.ifEmpty { listOf(READY_HELP.format(facts.devices)) }
}

/** Aide par état d'enregistrement ; `null` quand il n'y a rien à demander. */
private fun registrationHelp(state: RegistrationState): String? = when (state) {
    RegistrationState.REGISTERED -> null
    RegistrationState.AVAILABLE -> REGISTRATION_HELP
    RegistrationState.REGISTERING, RegistrationState.UNREGISTERING -> REGISTERING_HELP
    RegistrationState.UNAVAILABLE -> NO_REGISTRATION_HELP
}

private const val DEV_MODE_HELP =
    "Developer Mode inactive: Meta AI app → Settings → App Info → tap the version number 5×, then Enable"

private const val REGISTRATION_HELP =
    "registration to accept: the Meta AI app window is about to open, confirm it"

private const val REGISTERING_HELP =
    "registration in progress: stay in the Meta AI app until confirmed"

private const val NO_REGISTRATION_HELP =
    "no pair available for registration: glasses on, worn and paired in Meta AI"

private const val NO_DEVICE_HELP =
    "the SDK sees no glasses: pair them in the Meta AI app (glasses in pairing mode, Bluetooth on), then restart"

private const val CAMERA_HELP =
    "camera not granted to this app: Meta AI → Settings → App connections → SAM Vision (glasses spike) → Camera → Always allow"

private const val READY_HELP =
    "ready: %d pair(s) seen by the SDK, camera granted"