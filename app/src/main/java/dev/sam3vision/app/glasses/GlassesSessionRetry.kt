package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DeviceSessionError

/**
 * Rejou d'ouverture de session : un seul, et seulement quand le SDK semble
 * transitoirement pas prêt (`UNEXPECTED_ERROR`, ou session précédente pas
 * encore retombée). Pur, testé en JVM.
 */
internal data class SessionRetry(val used: Int = 0) {

    /** `true` = l'échec se rejoue une fois au lieu de se dire. */
    fun shouldRetry(error: DeviceSessionError): Boolean =
        used < MAX_RETRIES && (error == DeviceSessionError.UNEXPECTED_ERROR ||
            error == DeviceSessionError.SESSION_ALREADY_EXISTS)

    /** Rejou consommé : l'échec suivant se dira. */
    fun next(): SessionRetry = copy(used = used + 1)

    private companion object {
        const val MAX_RETRIES = 1
    }
}
