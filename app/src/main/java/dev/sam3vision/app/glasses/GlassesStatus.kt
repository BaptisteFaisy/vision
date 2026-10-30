package dev.sam3vision.app.glasses

/**
 * Les lignes d'état du bloc « Lunettes », en texte pur : elles se testent en JVM et
 * l'écran ne fait que les afficher. Tout y est en français lisible, sans jargon du SDK.
 */
fun statusLines(state: GlassesUiState): List<String> = listOf(
    sdkLine(state),
    registrationLine(state),
    deviceLine(state),
    sessionLine(state),
    micLine(state),
    videoLine(state),
)

/** La vidéo en cours d'enregistrement, et où la retrouver une fois la session fermée. */
private fun videoLine(state: GlassesUiState): String =
    "Vidéo : " + (state.recordingName?.let { "enregistrement → Movies/iSee/$it" } ?: "rien en cours")

private fun sdkLine(state: GlassesUiState): String {
    val dev = if (state.devMode) " (dev mode)" else ""
    return "SDK : ${if (state.sdkReady) "prêt" else "inactif"}$dev"
}

private fun registrationLine(state: GlassesUiState): String =
    "Enregistrement : ${if (state.registered) "enregistrée" else "non enregistrée"}"

private fun deviceLine(state: GlassesUiState): String {
    val device = state.deviceName ?: "aucune paire détectée"
    val battery = state.battery?.let { "$it %" } ?: "batterie inconnue"
    val wear = if (state.worn) "portées" else "non portées"
    return "Appareil : $device · $battery · $wear"
}

private fun sessionLine(state: GlassesUiState): String {
    val camera = if (state.cameraAllowed) "autorisée" else "non autorisée"
    val stream = if (state.streaming) "actif" else "arrêté"
    return "Session : ${state.phase.labelFr()} · caméra $camera · flux $stream"
}

/** Ligne discrète du micro : ce que la dictée peut réellement prendre, et quand. */
private fun micLine(state: GlassesUiState): String {
    val status = when {
        state.talkListening -> "écoute — parle, rappuie pour envoyer"
        state.micActive -> "actif"
        !state.micAllowed -> "non autorisé"
        GlassesAudioPlan.usable(state.connected, state.streaming, state.micAllowed) -> "prêt"
        else -> "en veille"
    }
    return "Micro lunettes : $status"
}