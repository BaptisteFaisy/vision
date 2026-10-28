package dev.sam3vision.app

import dev.sam3vision.core.share.ShareSend
import dev.sam3vision.core.share.shareSendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dernier problème du suivi, lisible dans les Réglages : refus du relais, relais
 * injoignable, ou position jamais reçue (localisation refusée). Sans ce retour, un
 * partage qui échoue est indiscernable d'un proche qui n'a rien à envoyer — c'est
 * exactement ce qui a laissé la carte de l'app vide sans un mot.
 */
object ShareHealth {

    private val _message = MutableStateFlow<String?>(null)

    /** Message courant, `null` quand tout est parti au dernier envoi. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Enregistre le résultat d'un envoi ; seul un succès efface l'alerte. */
    fun note(result: ShareSend) {
        when (result) {
            ShareSend.Sent -> clear()
            ShareSend.Skipped -> Unit
            else -> _message.value = shareSendMessage(result)
        }
    }

    /** Aucune position : permission refusée, GPS coupé ou fix pas encore obtenu. */
    fun noFix() {
        _message.value = "Position inconnue : autorise la localisation du téléphone pour être suivi."
    }

    fun clear() {
        _message.value = null
    }
}
