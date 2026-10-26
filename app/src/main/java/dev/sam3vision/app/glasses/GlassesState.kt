package dev.sam3vision.app.glasses

import dev.sam3vision.core.glasses.CaptureAction
import java.io.File

/** Phases de la session Device Access Toolkit, côté UI. */
enum class SessionPhase { IDLE, STARTING, STARTED, STOPPED }

/** Ce que l'écran « Lunettes » affiche. Volontairement plat : il est lu tel quel. */
data class GlassesUiState(
    val sdkReady: Boolean = false,
    val devMode: Boolean = false,
    val registered: Boolean = false,
    val deviceName: String? = null,
    val battery: Int? = null,
    val worn: Boolean = false,
    val phase: SessionPhase = SessionPhase.IDLE,
    val cameraAllowed: Boolean = false,
    val streaming: Boolean = false,
    val mockActive: Boolean = false,
    val message: GlassesMessage? = null,
) {
    val connected: Boolean get() = phase == SessionPhase.STARTED

    /** On peut ouvrir une session : enregistré, et pas déjà en train d'en ouvrir une. */
    val canConnect: Boolean get() = registered && phase.allowsConnect()

    /** L'appareil simulé ou réel est là. */
    val hasDevice: Boolean get() = deviceName != null
}

/** Ce que les lunettes demandent à l'app. */
sealed interface GlassesEvent {
    /** Photo reçue, écrite dans le cache interne : l'app peut l'analyser. */
    data class PhotoTaken(val file: File, val action: CaptureAction) : GlassesEvent

    /** Note à annoncer à voix haute (état, refus, conseil). Sa gravité est dans `message.kind`. */
    data class Note(val message: GlassesMessage) : GlassesEvent
}