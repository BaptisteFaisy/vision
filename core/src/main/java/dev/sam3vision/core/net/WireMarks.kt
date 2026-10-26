package dev.sam3vision.core.net

/**
 * Jalons d'une requête du direct, observés **par l'appelant** : ils permettent à la
 * chronologie de l'écran de séparer l'attente (`envoi → en-têtes`) de la descente du
 * corps et du parsing (`corps → objets`), au lieu d'un `rtt` unique.
 *
 * Le réseau ne connaît pas les horloges de l'UI : il se contente d'appeler ces deux
 * méthodes au bon moment. Les implémentations doivent être **non bloquantes** et sans
 * état lourd (elles tournent sur le thread réseau ou le dispatcher OkHttp).
 *
 * Sur le direct WebSocket, il n'y a pas d'en-têtes par frame : [onHeaders] n'est appelé
 * qu'au handshake (une fois par session), [onBody] à chaque message reçu. Un poste que
 * le transport ne peut pas dater reste donc absent — jamais un zéro inventé.
 */
interface WireMarks {
    /** Réponse HTTP : les en-têtes sont là, le corps n'est pas encore lu. */
    fun onHeaders()

    /** Corps complet reçu, **avant** le parsing JSON. */
    fun onBody()
}