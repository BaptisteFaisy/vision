package dev.sam3vision.glasses

import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.RegistrationState

/**
 * Règles de décision du cycle lunettes, sans Android et sans effet de bord.
 *
 * Ces fonctions ne journalisent rien, n'ouvrent aucune session et ne touchent
 * aucun objet DAT : elles disent seulement quoi faire d'un état, d'une erreur ou
 * d'une frame. Les appelants ([GlassesRegistrationPhase], [GlassesDeviceSession],
 * [GlassesFramePump], [GlassesFrameEncoder]) exécutent la décision ; ils ne la
 * prennent pas. C'est cette couche qui est prouvée sur JVM par `SessionPolicyTest`,
 * sans stubber le SDK.
 *
 * Compatible JVM : `RegistrationState` et `StreamError` sont des enums Kotlin
 * (vérifié par `javap` sur les AAR `mwdat-core` / `mwdat-camera` 1.0.1), donc
 * instanciables hors Android ; seule `getLocalizedDescription` exige un `Context`.
 */

/** Étranglement du flux : une frame toutes les 1,5 s suffit au LLM, au-delà on paie pour rien. */
internal const val FRAME_THROTTLE_MS = 1_500L

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
        RegistrationDecision(RegistrationStep.OPEN_SESSION, "lunettes enregistrées")

    RegistrationState.AVAILABLE ->
        RegistrationDecision(RegistrationStep.REQUEST_REGISTRATION, "en attente d'enregistrement")

    RegistrationState.REGISTERING -> logOnly("enregistrement en cours")
    RegistrationState.UNREGISTERING -> logOnly("désenregistrement en cours")
    RegistrationState.UNAVAILABLE -> logOnly("aucune paire de lunettes disponible")
}

private fun logOnly(message: String) =
    RegistrationDecision(RegistrationStep.LOG_ONLY, message)

/**
 * Attente à respecter avant d'envoyer la frame suivante, en millisecondes
 * (0 = envoi immédiat).
 *
 * L'horloge reste fournie par l'appelant (`SystemClock.elapsedRealtime()`), que
 * la JVM ne sait pas simuler sans Android : c'est la seule raison d'être de ce
 * paramètre. `lastSentAtMs = 0` (première frame) ne stérilise rien tant que
 * l'horloge a dépassé la fenêtre, ce qui est le cas d'`elapsedRealtime()`.
 *
 * Volontairement non borné vers le haut : une horloge qui recule fait attendre
 * plus que la fenêtre plutôt que d'envoyer deux frames dans la même seconde.
 */
internal fun throttleDelayMs(
    lastSentAtMs: Long,
    nowMs: Long,
    throttleMs: Long = FRAME_THROTTLE_MS,
): Long = (throttleMs - (nowMs - lastSentAtMs)).coerceAtLeast(0L)

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

/** Verdict sur une frame brute, avant toute copie ou conversion. */
internal enum class FrameVerdict {
    /** La frame porte une image : elle peut être encodée. */
    ENCODE,

    /** Bitstream HEVC/H.264 : décoder exigerait un `MediaCodec` complet, hors spike. */
    DROP_COMPRESSED,

    /** Frame de configuration / trame-clé : elle ne porte aucune image. */
    DROP_CODEC_CONFIG,

    /** Dimensions nulles : ni `IntArray` ni `Bitmap` ne sont dimensionnables. */
    DROP_DIMENSIONS,
}

/**
 * Faut-il encoder cette frame, et sinon pourquoi la jeter ?
 *
 * La compression est testée en premier : une frame compressée n'est jamais
 * décodée ici, même si le SDK y met aussi le drapeau de configuration.
 */
internal fun frameVerdict(
    isCompressed: Boolean,
    isCodecConfig: Boolean,
    width: Int,
    height: Int,
): FrameVerdict = when {
    isCompressed -> FrameVerdict.DROP_COMPRESSED
    isCodecConfig -> FrameVerdict.DROP_CODEC_CONFIG
    width <= 0 || height <= 0 -> FrameVerdict.DROP_DIMENSIONS
    else -> FrameVerdict.ENCODE
}

// ---- Démarrage sur de vraies lunettes ----------------------------------------

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
    "Developer Mode inactif : app Meta AI → Settings → App Info → taper 5× le numéro de version, puis Enable"

private const val REGISTRATION_HELP =
    "enregistrement à accepter : la fenêtre de l'app Meta AI va s'ouvrir, confirme-la"

private const val REGISTERING_HELP =
    "enregistrement en cours : reste dans l'app Meta AI jusqu'à la confirmation"

private const val NO_REGISTRATION_HELP =
    "aucune paire disponible pour l'enregistrement : lunettes allumées, portées et appairées dans Meta AI"

private const val NO_DEVICE_HELP =
    "le SDK ne voit aucune lunette : appaire la paire dans l'app Meta AI (lunettes en mode appairage, Bluetooth allumé), puis relance"

private const val CAMERA_HELP =
    "caméra non accordée à cette app : Meta AI → Settings → App connections → SAM Vision (spike lunettes) → Caméra → Always allow"

private const val READY_HELP =
    "prêt : %d paire(s) vue(s) par le SDK, caméra accordée"