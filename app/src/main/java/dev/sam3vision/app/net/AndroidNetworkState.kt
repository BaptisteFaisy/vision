package dev.sam3vision.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.sam3vision.core.net.NetworkState

/**
 * Ce que le téléphone sait du réseau, poussé au client du direct (`:core` ne sonde rien :
 * il lit deux champs, sans permission ni polling).
 *
 * Pourquoi ce fichier existe : avant lui, l'app ne savait pas qu'elle changeait de réseau.
 * Un passage Wi-Fi → 4G laissait le WebSocket `/live/ws` accroché à l'interface morte ; la
 * perte n'était découverte qu'au délai expiré de la frame suivante (10 s, 60 s à froid).
 *
 * Le suivi est **événementiel** (`registerDefaultNetworkCallback`) : pas de réveil
 * périodique, pas de batterie dépensée. `iface` ne change qu'entre deux interfaces
 * réellement différentes, ce qui suffit au client pour déclarer mort-né le socket de
 * l'ancienne et rouvrir sur la nouvelle.
 *
 * Lecture sans verrou : deux champs `@Volatile`, écrits par le callback système (thread
 * binder) et lus par la boucle de frames (thread réseau). Aucune allocation, aucun await.
 */
class AndroidNetworkState(context: Context) : NetworkState {

    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var online: Boolean = true

    @Volatile
    private var ifaceName: String? = null

    override val available: Boolean get() = online

    override val iface: String? get() = ifaceName

    /** Branche le suivi ; le premier état est lu tout de suite (pas d'attente de callback). */
    fun start() {
        val cm = manager ?: return
        readNow(cm)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = readNow(cm)

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    note(caps)
                }

                override fun onLost(network: Network) {
                    // Le réseau par défaut est tombé : `onAvailable` du suivant (ou une
                    // absence durable) tranche. On republie l'état réel plutôt que de
                    // supposer « hors ligne » — un Wi-Fi qui bascule vers la 4G enchaîne
                    // `onLost` puis `onAvailable` dans la même passe.
                    readNow(cm)
                }
            })
        }
    }

    /** État courant lu au système : interface par défaut et capacités. */
    private fun readNow(cm: ConnectivityManager) {
        val network = cm.activeNetwork
        if (network == null) {
            online = false
            ifaceName = null
            return
        }
        note(cm.getNetworkCapabilities(network))
    }

    /** Traduit les capacités en (disponible, nom d'interface stable). */
    private fun note(caps: NetworkCapabilities?) {
        if (caps == null) {
            online = false
            ifaceName = null
            return
        }
        // INTERNET (et non VALIDATED) : la validation captive arrive quelques secondes
        // après l'association Wi-Fi, et la prendre pour « hors ligne » refuserait des
        // frames parfaitement possibles. Un portail captif se manifestera par des échecs
        // réels, que la veille de panne annonce.
        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ifaceName = ifaceOf(caps)
    }

    /** Nom d'interface : deux réseaux de même transport restent la même route. */
    private fun ifaceOf(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> IFACE_VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> IFACE_WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> IFACE_CELL
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> IFACE_ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> IFACE_BLUETOOTH
        else -> IFACE_OTHER
    }

    private companion object {
        const val IFACE_WIFI = "wifi"
        const val IFACE_CELL = "cell"
        const val IFACE_VPN = "vpn"
        const val IFACE_ETHERNET = "ethernet"
        const val IFACE_BLUETOOTH = "bluetooth"
        const val IFACE_OTHER = "other"
    }
}
