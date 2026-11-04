package dev.sam3vision.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dev.sam3vision.core.net.BackendRoute
import dev.sam3vision.core.net.drainBackendPools
import dev.sam3vision.core.net.sameCellularRouteInstalled
import dev.sam3vision.core.net.wantsCellularRoute

/**
 * Trafic backend sur le cellulaire pendant la session lunettes (correctif
 * « perte 5G » + lunettes Meta).
 *
 * L'app Meta AI monte un Wi-Fi local vers les lunettes, sans internet, qui
 * devient la route par défaut du téléphone ; sans ceci, tout le backend iSee
 * (direct `/live/ws`, `/health`, LLM, ASR, relay) part dans cette route morte.
 * Ici, pendant la session lunettes uniquement :
 * - `requestNetwork(TRANSPORT_CELLULAR + INTERNET)` garde le cellulaire monté
 *   à côté du Wi-Fi lunettes (jamais de `bindProcessToNetwork` global : le
 *   flux local des lunettes et l'audio Bluetooth gardent leurs routes) ;
 * - la `socketFactory` ET la résolution DNS du réseau cellulaire sont poussées
 *   dans [BackendRoute] **si et seulement si** le réseau par défaut n'est pas
 *   validé (sur Wi-Fi maison validé, rien ne change : pas de données mobiles
 *   pour rien) — sans le DNS, la résolution suivrait la route morte
 *   (`UnknownHost`) alors que les sockets partiraient bien sur le cellulaire ;
 * - à la bascule, les pools OkHttp sont vidés et le WebSocket est rouvert via
 *   le suffixe `+cell` d'[AndroidNetworkState.iface] (invalidation d'interface
 *   existante, aucun changement dans le client du direct).
 *
 * Fenêtre pilotée par la phase session (`AppNavGlasses` + `MainActivity`) :
 * [acquire] idempotent à `STARTING`/`STARTED`, [release] idempotent sinon et
 * en fin de composition. Ne lève jamais (permission manquante, service
 * absent : repli silencieux sur la route par défaut).
 */
object GlassesNetworkRoute {

    private const val TAG = "sam3vision"

    private val lock = Any()

    private var started = false
    private var manager: ConnectivityManager? = null
    private var cellular: Network? = null
    private var defaultValidated = true
    /** Clé (`Network.toString()`, netId) du réseau dont la fabrique est imposée, `null` = aucune. */
    private var installedKey: String? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Demande le cellulaire et surveille la validation du défaut. Idempotent :
     * les émissions d'état répétées en session ne ré-enregistrent rien.
     */
    fun acquire(context: Context) {
        synchronized(lock) {
            if (started) return
            started = true
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager = cm
            if (cm == null) {
                Log.w(TAG, "route lunettes : pas de ConnectivityManager, route par défaut")
                return
            }
            readValidatedLocked(cm)
            watchDefaultLocked(cm)
            requestCellularLocked(cm)
        }
    }

    /** Libère les deux suivis et rend le backend à la route par défaut. Idempotent. */
    fun release() {
        val changed = synchronized(lock) {
            if (!started) return
            started = false
            val cm = manager
            manager = null
            cellular = null
            defaultValidated = true
            installedKey = null
            runCatching { cm?.unregisterNetworkCallback(cellularCallback ?: return@runCatching) }
            runCatching { cm?.unregisterNetworkCallback(defaultCallback ?: return@runCatching) }
            cellularCallback = null
            defaultCallback = null
            BackendRoute.clear()
        }
        if (changed) {
            drainBackendPools()
            Log.i(TAG, "route lunettes : backend rendu à la route par défaut")
        }
    }

    /** Surveille la validation du réseau par défaut (appel sous [lock]). */
    private fun watchDefaultLocked(cm: ConnectivityManager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onDefaultChanged(cm)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                onDefaultCaps(caps)

            override fun onLost(network: Network) = onDefaultChanged(cm)
        }
        defaultCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "route lunettes : suivi du défaut refusé : ${it.message}") }
    }

    /** Demande le maintien du cellulaire à côté du Wi-Fi lunettes (sous [lock]). */
    private fun requestCellularLocked(cm: ConnectivityManager) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onCellular(network)

            override fun onLost(network: Network) = onCellularLost(network)
        }
        cellularCallback = callback
        // `CHANGE_NETWORK_STATE` requis : sans elle, refus silencieux (repli défaut).
        runCatching { cm.requestNetwork(request, callback) }
            .onFailure { Log.w(TAG, "route lunettes : cellulaire refusé : ${it.message}") }
    }

    /** Cellulaire monté : réévalue la route (thread binder). */
    private fun onCellular(network: Network) {
        synchronized(lock) {
            cellular = network
            applyLocked()
        }
    }

    /** Cellulaire perdu : le défaut reprend, même non validé (thread binder). */
    private fun onCellularLost(network: Network) {
        synchronized(lock) {
            if (cellular == network) cellular = null
            applyLocked()
        }
    }

    /** Défaut changé ou tombé : relit sa validation au système (thread binder). */
    private fun onDefaultChanged(cm: ConnectivityManager) {
        synchronized(lock) {
            if (manager !== cm) return
            readValidatedLocked(cm)
            applyLocked()
        }
    }

    /** Capacités du défaut reçues : met à jour sa validation (thread binder). */
    private fun onDefaultCaps(caps: NetworkCapabilities) {
        synchronized(lock) {
            defaultValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            applyLocked()
        }
    }

    /** Validation du défaut lue au système (appel sous [lock]). */
    private fun readValidatedLocked(cm: ConnectivityManager) {
        val active = cm.activeNetwork
        val caps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        defaultValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /**
     * Applique la route voulue (appel sous [lock]) : cellulaire si le défaut
     * n'est pas validé et le cellulaire monté, défaut sinon. Ne vide les pools
     * qu'à la bascule effective — les rappels répétés pour le même réseau
     * (capacités, signal) sont des no-ops, sinon chaque rappel tuerait le
     * WebSocket du direct (voir [sameCellularRouteInstalled]).
     */
    private fun applyLocked() {
        val network = cellular
        val want = wantsCellularRoute(network != null, defaultValidated)
        val key = network?.toString()
        if (want && sameCellularRouteInstalled(BackendRoute.active, installedKey, key)) return
        val changed = if (want && network != null) {
            installedKey = key
            BackendRoute.install(network.socketFactory) { host -> network.getAllByName(host).toList() }
        } else {
            installedKey = null
            BackendRoute.clear()
        }
        if (!changed) return
        drainBackendPools()
        Log.i(TAG, "route lunettes : backend sur " + if (want) "cellulaire" else "route par défaut")
    }
}
