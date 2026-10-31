package dev.sam3vision.app.glasses.bench

import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.CoroutineStart

/** 24 i/s : fluide pour du suivi visuel, économe en batterie. */
private const val FRAME_RATE = 24

/**
 * Phase session puis caméra puis flux : rien ne démarre avant `STARTED`.
 *
 * Chaque étape est armée séparément (`sessionLatch`, `cameraLatch`) et
 * ses collecteurs sont posés **avant** l'appel `start()` correspondant, pour ne
 * pas perdre un état ou une erreur émis pendant la connexion.
 */
internal suspend fun GlassesRuntime.runSession(session: DeviceSession) {
    this.session = session
    launchSafe(start = CoroutineStart.UNDISPATCHED) {
        session.errors.collect { error -> onLog("session error: ${describe(error)}") }
    }
    launchSafe(start = CoroutineStart.UNDISPATCHED) {
        session.state.collect { state -> onSessionState(state, session) }
    }
    io { session.start() }
    onLog("session started")
}

private suspend fun GlassesRuntime.onSessionState(
    state: DeviceSessionState,
    session: DeviceSession,
) {
    onLog("session: $state")
    // La caméra n'a de sens qu'une fois la session réellement connectée.
    if (state == DeviceSessionState.STARTED) addCamera(session)
}

private suspend fun GlassesRuntime.addCamera(session: DeviceSession) {
    if (!cameraLatch.arm()) return
    val configuration = StreamConfiguration(
        videoQuality = VideoQuality.MEDIUM,
        frameRate = FRAME_RATE,
    )
    val result = io { session.addCamera(configuration) }
    result.fold(
        onSuccess = { camera -> launchSafe { startStream(camera) } },
        onFailure = { error, _ -> onLog("camera refused: ${describe(error)}") },
    )
}

private suspend fun GlassesRuntime.startStream(camera: Camera) {
    this.camera = camera
    onLog("camera added")
    val stream = camera.stream
    this.stream = stream
    // Abonnement avant start() : état et erreurs émis pendant le handshake sont
    // ainsi vus plutôt que perdus.
    launchSafe(start = CoroutineStart.UNDISPATCHED) {
        stream.state.collect { state -> onLog("stream: $state") }
    }
    launchSafe(start = CoroutineStart.UNDISPATCHED) {
        stream.errorStream.collect { error -> onStreamError(error) }
    }
    launchSafe { consumeFrames() }
    launchSafe { collectFrames(stream) }
    val result = io { stream.start() }
    result.fold(
        onSuccess = { onLog("stream started") },
        onFailure = { error, _ -> onLog("stream refused: ${describe(error)}") },
    )
}