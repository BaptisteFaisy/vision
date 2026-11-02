package dev.sam3vision.core.net

/**
 * Ce que le client du direct sait du réseau, **poussé** par la plateforme : `:core` ne
 * sonde rien (aucune permission, aucun polling) et ne connaît pas Android.
 *
 * [available] à `false` = perte de connexion **connue** : la frame échoue localement, en
 * O(1), sans DNS, sans TCP, sans handshake et sans consommer de délai — la radio reste au
 * repos et la prochaine frame repart dès le retour annoncé.
 *
 * [iface] identifie l'interface en service (`wifi`, `cellulaire`, `vpn`…) : un simple
 * changement de valeur suffit à déclarer mort-né le socket de l'ancienne interface, sans
 * registre d'abonnés ni génération à propager.
 */
interface NetworkState {

    /** Vrai si une interface est en service (valeur par défaut : « on ne sait pas »). */
    val available: Boolean

    /** Interface en service, `null` = inconnue (aucune invalidation sur cette base). */
    val iface: String?

    companion object {
        /**
         * Défaut : « on ne sait pas, donc on ne bloque rien ». C'est le comportement
         * d'avant l'injection — les tests et les transports sans réseau restent inchangés.
         */
        val Always: NetworkState = object : NetworkState {
            override val available: Boolean = true
            override val iface: String? = null
        }
    }
}

/**
 * Faux état réseau des tests JVM (et du câblage sans plateforme) : deux champs mutables,
 * aucune horloge, aucun rappel. Un test bascule [available] / [iface] pour rejouer une
 * coupure ou un changement d'interface.
 */
class FakeNetworkState(
    override var available: Boolean = true,
    override var iface: String? = null,
) : NetworkState
