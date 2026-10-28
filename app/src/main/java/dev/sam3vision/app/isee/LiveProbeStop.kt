package dev.sam3vision.app.isee

/**
 * Arrêt net de la sonde SAM : à part (même raison que `ProbeWiring.kt`), car
 * `LiveProbe.kt` est au plafond des 500 lignes.
 *
 * Caméra coupée ou app en arrière-plan, ce n'est pas assez de ne plus envoyer de
 * frames : les WebSockets restés ouverts garderaient le conteneur Modal allumé
 * (facturé). Ici les sessions sont fermées et oubliées — la carte retombe à zéro —
 * et la séance suivante rouvre des sessions neuves via `configure`.
 */
internal fun LiveProbe.stop() {
    resetPipeline()
    closeSessions()
    clear()
}

/**
 * Ferme les deux sessions (normale + froide) et les oublie : la réponse en vol est
 * révoquée par `close`, les frames suivantes sont refusées (`wantsFrame` sans
 * session répond non). Idempotent : sans session, ne fait rien.
 */
internal fun LiveProbe.closeSessions() {
    session?.close()
    session = null
    coldSession?.close()
    coldSession = null
}
