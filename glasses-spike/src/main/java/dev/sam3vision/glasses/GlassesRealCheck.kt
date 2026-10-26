package dev.sam3vision.glasses

import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.ChargingState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.HingeState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState

/**
 * Lecture de l'état DAT réel, pour le mode « Lunettes » : ce que le SDK voit,
 * résumé en lignes de journal lisibles.
 *
 * Toute la valeur est dans le diagnostic : le démarrage réel ne casse pas quand
 * rien n'est apprêté, il **attend** (la session reste en attente, aucune erreur
 * n'est émise). Sans ces lignes, l'utilisateur ne peut pas distinguer « pas de
 * lunette appairée » de « Developer Mode inactif » ou de « caméra refusée par
 * l'app Meta AI » — trois gestes différents.
 *
 * Les décisions sont dans [realStartAdvice] (fonction pure, testée sur JVM) :
 * ici on ne fait que lire le SDK et formater, jamais d'exception qui remonte —
 * un `Wearables` non initialisé rend des valeurs par défaut plutôt qu'un crash.
 */
internal suspend fun realStartFacts(): RealStartFacts = RealStartFacts(
    devMode = runCatching { Wearables.isDevMode }.getOrDefault(false),
    registration = runCatching { Wearables.registrationState.value }
        .getOrDefault(RegistrationState.UNAVAILABLE),
    devices = runCatching { Wearables.devices.value.size }.getOrDefault(0),
    cameraAllowed = cameraAllowed(),
)

/** `checkPermissionStatus` est `suspend` et peut échouer : on ne veut qu'un booléen. */
private suspend fun cameraAllowed(): Boolean =
    runCatching { Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() }
        .getOrNull() is PermissionStatus.Granted

/**
 * Une ligne par paire que le SDK connaît, avec ce qui explique qu'un flux ne
 * parte pas : lien, batterie, portée, charnière — plus la compatibilité, qui
 * demande une mise à jour côté lunettes ou côté SDK quand elle n'est pas bonne.
 */
internal fun deviceLines(): List<String> = runCatching {
    Wearables.devices.value.mapNotNull(::describeDevice)
}.getOrDefault(emptyList())

/**
 * Nom de la paire que le SDK connaît, s'il y en a une : c'est lui qui fait
 * préférer cette sortie Bluetooth aux autres (une paire d'écouteurs branchée sur
 * le téléphone ne doit pas prendre la voix destinée aux lunettes).
 */
internal fun knownDeviceName(): String? = runCatching {
    Wearables.devices.value.firstNotNullOfOrNull { id ->
        Wearables.devicesMetadata[id]?.value?.name?.takeIf { it.isNotBlank() }
    }
}.getOrNull()

private fun describeDevice(id: DeviceIdentifier): String? {
    val device = Wearables.devicesMetadata[id]?.value ?: return null
    return buildString {
        append("paire ${device.deviceType.name.lowercase()} : ${device.name}")
        append(" · lien ${linkLabel(device.linkState)}")
        append(" · batterie ${device.batteryLevel} %${chargeLabel(device.chargingState)}")
        append(" · ${donLabel(device.donState)} · ${hingeLabel(device.hingeState)}")
        compatibilityHelp(device.compatibility)?.let { append(" · $it") }
    }
}

/** Seule une compatibilité autre que `COMPATIBLE` mérite une phrase. */
private fun compatibilityHelp(compatibility: DeviceCompatibility): String? = when (compatibility) {
    DeviceCompatibility.COMPATIBLE, DeviceCompatibility.UNDEFINED -> null
    DeviceCompatibility.DEVICE_UPDATE_REQUIRED -> "firmware des lunettes à mettre à jour (app Meta AI)"
    DeviceCompatibility.SDK_UPDATE_REQUIRED -> "SDK DAT trop ancien pour ces lunettes"
}

private fun linkLabel(state: LinkState): String = when (state) {
    LinkState.CONNECTED -> "connectée"
    LinkState.CONNECTING -> "connexion en cours"
    LinkState.DISCONNECTED -> "déconnectée"
}

private fun donLabel(state: DonState): String = when (state) {
    DonState.DONNED -> "portées"
    DonState.DOFFED -> "non portées"
    DonState.UNKNOWN -> "portée inconnue"
}

private fun hingeLabel(state: HingeState): String = when (state) {
    HingeState.OPEN -> "dépliées"
    HingeState.CLOSED -> "repliées"
    HingeState.UNKNOWN -> "charnière inconnue"
}

/** La charge n'est dite que quand elle est en cours : ailleurs, le niveau suffit. */
private fun chargeLabel(state: ChargingState): String = when (state) {
    ChargingState.CHARGING -> " (en charge)"
    ChargingState.NOT_CHARGING, ChargingState.UNKNOWN -> ""
}

/** Le journal complet d'un démarrage réel : conseils d'abord, puis chaque paire. */
internal suspend fun logRealStart(log: (String) -> Unit) {
    val facts = realStartFacts()
    realStartAdvice(facts).forEach(log)
    deviceLines().forEach(log)
}