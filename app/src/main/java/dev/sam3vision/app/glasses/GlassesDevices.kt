package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DonState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Suit la **première** paire connue du SDK et publie ce que l'écran affiche : nom, batterie,
 * portée. Le SDK peut connaître plusieurs paires, une seule suffit ici. Sorti du contrôleur
 * pour l'y laisser mince, il ne décide rien : il traduit.
 */
internal class GlassesDeviceWatch(
    private val scope: CoroutineScope,
    private val update: ((GlassesUiState) -> GlassesUiState) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        scope.launch { Wearables.devices.collect { identifiers -> bind(identifiers) } }
    }

    private fun bind(identifiers: Set<DeviceIdentifier>) {
        job?.cancel()
        val flow = identifiers.firstOrNull()?.let { Wearables.devicesMetadata[it] }
        if (flow == null) {
            job = null
            update { it.copy(deviceName = null, battery = null, worn = false) }
            return
        }
        job = scope.launch {
            flow.collect { device ->
                update {
                    it.copy(
                        deviceName = device.name,
                        battery = device.batteryLevel.takeIf { level -> level > 0 },
                        worn = device.donState == DonState.DONNED,
                    )
                }
            }
        }
    }
}
