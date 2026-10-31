package dev.sam3vision.app.glasses

/**
 * Les lignes d'état du bloc « Lunettes », en texte pur : elles se testent en JVM et
 * l'écran ne fait que les afficher. Tout y est en anglais lisible, sans jargon du SDK.
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
    "Video: " + (state.recordingName?.let { "recording → Movies/iSee/$it" } ?: "nothing running")

private fun sdkLine(state: GlassesUiState): String {
    val dev = if (state.devMode) " (dev mode)" else ""
    return "SDK: ${if (state.sdkReady) "ready" else "inactive"}$dev"
}

private fun registrationLine(state: GlassesUiState): String =
    "Registration: ${if (state.registered) "registered" else "not registered"}"

private fun deviceLine(state: GlassesUiState): String {
    val device = state.deviceName ?: "no pair detected"
    val battery = state.battery?.let { "$it %" } ?: "battery unknown"
    val wear = if (state.worn) "worn" else "not worn"
    return "Device: $device · $battery · $wear"
}

private fun sessionLine(state: GlassesUiState): String {
    val camera = if (state.cameraAllowed) "allowed" else "not allowed"
    val stream = if (state.streaming) "active" else "stopped"
    return "Session: ${state.phase.label()} · camera $camera · stream $stream"
}

/** Ligne discrète du micro : ce que la dictée peut réellement prendre, et quand. */
private fun micLine(state: GlassesUiState): String {
    val status = when {
        state.talkListening -> "listening — speak, press again to send"
        state.micActive -> "active"
        !state.micAllowed -> "not allowed"
        GlassesAudioPlan.usable(state.connected, state.streaming, state.micAllowed) -> "ready"
        else -> "standby"
    }
    return "Glasses mic: $status"
}