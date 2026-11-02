package dev.sam3vision.app.net

import android.content.Context
import dev.sam3vision.core.net.NetworkState

/**
 * Point d'installation unique de l'état réseau du téléphone, lu par la fabrique du direct
 * ([dev.sam3vision.app.LiveMaskTransport.clientFor]).
 *
 * Pourquoi un singleton : la fabrique est appelée depuis deux écrans et depuis les tests,
 * sans contexte Android. Elle ne peut donc pas construire [AndroidNetworkState] elle-même ;
 * `SamApp` l'installe une fois au démarrage, et la fabrique lit ce point.
 *
 * Tant que rien n'est installé, [state] rend [NetworkState.Always] : « on ne sait pas, donc
 * on ne bloque rien » — le comportement d'avant, jamais une panne inventée.
 */
object NetMonitor {

    @Volatile
    private var installed: NetworkState = NetworkState.Always

    /** État réseau courant, sans allocation et sans verrou. */
    fun state(): NetworkState = installed

    /**
     * Branche le suivi système. Idempotent : le premier appel gagne, les suivants ne
     * remplacent pas un état déjà installé (un test peut passer par [reset]).
     */
    fun install(context: Context) {
        if (installed !== NetworkState.Always) return
        val monitor = AndroidNetworkState(context.applicationContext)
        monitor.start()
        installed = monitor
    }

    /** Rend le défaut (tests JVM, ou appareil sans service de connectivité). */
    fun reset() {
        installed = NetworkState.Always
    }
}
