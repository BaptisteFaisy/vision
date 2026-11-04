package dev.sam3vision.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import dev.sam3vision.core.net.BackendRoute
import dev.sam3vision.core.net.FlapGuard
import dev.sam3vision.core.net.NetworkState
import dev.sam3vision.core.net.ifaceRouteKey
import dev.sam3vision.core.net.netIfaceKey

/**
 * Vue publiée du réseau : couple (disponible, clé) lu d'un bloc, jamais
 * déchiré (l'ancien couple venait de deux volatils écrits séparément).
 */
internal data class NetView(val online: Boolean, val iface: String?)

/**
 * Ce que le téléphone sait du réseau, poussé au client du direct (`:core` ne sonde rien :
 * il lit deux champs, sans permission ni polling).
 *
 * Pourquoi ce fichier existe : avant lui, l'app ne savait pas qu'elle changeait de réseau.
 * Un passage Wi-Fi → 4G laissait le WebSocket `/live/ws` accroché à l'interface morte ; la
 * perte n'était découverte qu'au délai expiré de la frame suivante (10 s, 60 s à froid).
 *
 * Le suivi est **événementiel** (`registerDefaultNetworkCallback`) : pas de réveil
 * périodique, pas de batterie dépensée. Le brut système ([setRaw]) ne sort jamais
 * tel quel : la garde anti-flap ne publie que ce qui **dure** — un trou de handover
 * (< 2 s, `activeNetwork` transitoirement nul) est absorbé, un vrai changement est
 * publié à 2 s, et le retour en ligne passe en immédiat (`fastPath`).
 *
 * Lecture sous un seul verrou : disponible + clé composés dans le même [NetView],
 * marque de route lue atomiquement — aucun couple déchiré entre deux sources.
 */
class AndroidNetworkState(
    context: Context,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) : NetworkState {

    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val lock = Any()
    private var rawOnline: Boolean = true
    private var rawBase: String? = null
    private val guard = FlapGuard<NetView>(
        fastPath = { candidate, stable -> candidate.online && !stable.online },
    )

    override val available: Boolean get() = stable().online

    /**
     * Clé en service : base système (`wifi#109`) + tag de route (`+cell#r3` sur
     * le cellulaire imposé, `#r4` sur la route par défaut). Tout swap effectif
     * y change la clé et le client du direct rouvre son socket — sans le tag,
     * le Wi-Fi lunettes gardait le même nom sur une route morte.
     */
    override val iface: String? get() = stable().iface

    /**
     * Snapshot atomique : brut + marque de route composés sous [lock], puis
     * publiés par la garde. Ordre des verrous unique ([lock] → garde →
     * [BackendRoute], jamais d'appel inverse) : pas d'interblocage.
     */
    private fun stable(): NetView = synchronized(lock) {
        guard.sample(
            NetView(rawOnline, rawBase?.let { ifaceRouteKey(it, BackendRoute.mark()) }),
            clockMs(),
        )
    }

    /** Brut système retenu (écrit par les callbacks, lu par [stable]). */
    private fun setRaw(online: Boolean, base: String?) = synchronized(lock) {
        rawOnline = online
        rawBase = base
    }

    /** Branche le suivi ; le premier état est lu tout de suite (pas d'attente de callback). */
    fun start() {
        val cm = manager ?: return
        readNow(cm)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = readNow(cm)

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    note(network, caps)
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
            setRaw(false, null)
            return
        }
        note(network, cm.getNetworkCapabilities(network))
    }

    /** Traduit le réseau et ses capacités en (disponible, clé de réseau stable). */
    private fun note(network: Network, caps: NetworkCapabilities?) {
        if (caps == null) {
            setRaw(false, null)
            return
        }
        // INTERNET (et non VALIDATED) : la validation captive arrive quelques secondes
        // après l'association Wi-Fi, et la prendre pour « hors ligne » refuserait des
        // frames parfaitement possibles. Un portail captif se manifestera par des échecs
        // réels, que la veille de panne annonce.
        setRaw(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), netIfaceKey(ifaceOf(caps), network.toString()))
    }

    /** Transport du réseau : la clé complète ajoute le netId ([netIfaceKey]). */
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
