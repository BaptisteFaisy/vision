package dev.sam3vision.app.glasses

/**
 * Écoute auto d'ouverture de session : armée quand la session attache sa
 * caméra, tirée au premier flux montant, une seule fois par session. Pure :
 * aucun SDK, aucun Android — testée en JVM.
 */
internal class GlassesAutoListen(private val listen: () -> Unit) {
    private var armed = false

    /** Session prête : l'écoute partira au premier flux montant. */
    fun arm() {
        armed = true
    }

    /** Flux caméra : la première montée ouvre l'écoute, les suivantes rien. */
    fun onStreaming(streaming: Boolean) {
        if (!streaming || !armed) return
        armed = false
        listen()
    }

    /** Session tombée : une écoute en attente ne partira pas. */
    fun disarm() {
        armed = false
    }
}
