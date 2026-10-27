package dev.sam3vision.app.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import com.meta.wearable.dat.inputs.types.CapturePressType
import dev.sam3vision.app.PcmSource
import dev.sam3vision.core.glasses.CaptureAction
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** WAV des dictées prises avec le micro des lunettes, sous `filesDir`. */
private const val AUDIO_DIR = "glasses-audio"

/**
 * Chef d'orchestre des lunettes Ray-Ban Meta : enregistrement auprès de l'app Meta AI,
 * session Device Access Toolkit, caméra et bouton capture.
 *
 * Aucune clé et aucun compte développeur : en Developer Mode le SDK s'enregistre
 * localement (APPLICATION_ID et CLIENT_TOKEN à « 0 » dans le manifeste).
 */
class GlassesController(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(GlassesUiState())
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 8)

    val state: StateFlow<GlassesUiState> = _state
    val events: SharedFlow<GlassesEvent> = _events

    // Implémentation (Mock Device Kit) en `app/src/debug/` : la dépendance n'est pas
    // embarquée dans l'APK de release, d'où la fabrique réflexive [mockDevice].
    private val mock: GlassesMockDevice = mockDevice(context)
    private var session: DeviceSession? = null
    private var sessionWatch: Job? = null
    private var camera: GlassesCamera? = null
    private var inputs: GlassesInputs? = null
    private var activity: Activity? = null
    private var deviceJob: Job? = null
    private var started = false

    /** Appelé depuis [android.app.Activity.onCreate] et `onNewIntent`. */
    fun bind(activity: Activity) {
        this.activity = activity
    }

    /** Initialise le SDK une seule fois, puis suit l'enregistrement et les appareils. */
    fun initialize() {
        if (started) return
        started = true
        Wearables.initialize(context).fold(
            onSuccess = { onSdkReady() },
            onFailure = { error, _ ->
                // Rotation : le SDK est un singleton déjà initialisé par l'activité précédente.
                // Ce n'est pas une panne ; sans ce cas, tout le bloc Lunettes restait inerte
                // jusqu'à la mort du processus.
                if (error == WearablesError.ALREADY_INITIALIZED) onSdkReady()
                else note(GlassesMessages.sdkUnavailable(error.description))
            },
        )
    }

    private fun onSdkReady() {
        _state.update { current -> current.copy(sdkReady = true, devMode = Wearables.isDevMode) }
        watchRegistration()
        watchDevices()
    }

    /**
     * Suit l'enregistrement. Le flux d'erreurs est écouté aussi : sans lui, un enregistrement
     * refusé (app Meta AI absente, refus de l'utilisateur) ne disait rien du tout — l'écran
     * restait sur « non enregistrée » sans explication.
     */
    private fun watchRegistration() {
        scope.launch {
            Wearables.registrationState.collect { state ->
                _state.update { it.copy(registered = state == RegistrationState.REGISTERED) }
            }
        }
        scope.launch {
            Wearables.registrationErrorStream
                .filter { error -> registrationErrorWorthTelling(error) }
                .collect { error -> note(GlassesMessages.registrationFailed(error.description)) }
        }
    }

    private fun watchDevices() {
        scope.launch {
            Wearables.devices.collect { identifiers -> bindFirstDevice(identifiers) }
        }
    }

    /** Le SDK peut connaître plusieurs paires : on suit la première, ça suffit ici. */
    private fun bindFirstDevice(identifiers: Set<DeviceIdentifier>) {
        deviceJob?.cancel()
        val flow = identifiers.firstOrNull()?.let { Wearables.devicesMetadata[it] }
        if (flow == null) {
            deviceJob = null
            _state.update { it.copy(deviceName = null, battery = null, worn = false) }
            return
        }
        deviceJob = scope.launch {
            flow.collect { device ->
                _state.update {
                    it.copy(
                        deviceName = device.name,
                        battery = device.batteryLevel.takeIf { level -> level > 0 },
                        worn = device.donState == DonState.DONNED,
                    )
                }
            }
        }
    }

    /** Enregistre l'app auprès de l'app Meta AI : la confirmation se fait côté Meta AI. */
    fun register() {
        initialize()
        val host = activity ?: return note(GlassesMessages.noActivity())
        Wearables.startRegistration(host)
        note(GlassesMessages.registrationStarted())
    }

    fun unregister() {
        activity?.let { Wearables.startUnregistration(it) }
    }

    /** Retour d'enregistrement ou d'invocation vocale depuis l'app Meta AI. */
    fun handleIntent(intent: Intent) {
        val host = activity ?: return
        Wearables.handleIntent(intent) { request -> request.continueRegistration(host) }
    }

    fun connect() {
        initialize()
        // Une session terminée (paire retirée, Bluetooth perdu) ne tient pas lieu de session :
        // sans cet oubli, le bouton restait grisé et l'utilisateur n'avait plus aucune prise.
        if (session != null && !_state.value.phase.allowsConnect()) {
            return note(GlassesMessages.sessionAlreadyRunning())
        }
        dropSession()
        _state.update { it.copy(phase = SessionPhase.STARTING) }
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = ::startSession,
            onFailure = { error, _ ->
                _state.update { it.copy(phase = SessionPhase.IDLE) }
                note(GlassesMessages.sessionRefused(error.description))
            },
        )
    }

    private fun startSession(created: DeviceSession) {
        session = created
        // Les deux collecteurs meurent avec la session, sinon une session passée
        // réécrit l'état de la nouvelle (et chaque reconnexion en laissait deux de plus).
        sessionWatch = scope.launch {
            launch { created.state.collect { state -> onSessionState(created, state) } }
            launch {
                created.errors.collect { error -> note(GlassesMessages.sessionInterrupted(error.description)) }
            }
        }
        created.start()
    }

    /** Oublie la session courante et ses collecteurs : elle ne peut plus imposer d'état. */
    private fun dropSession() {
        sessionWatch?.cancel()
        sessionWatch = null
        session = null
    }

    private fun onSessionState(created: DeviceSession, state: DeviceSessionState) {
        // Une session oubliée ne parle plus : sinon elle réécrit l'état de la nouvelle.
        if (created !== session) return
        _state.update { it.copy(phase = state.toPhase()) }
        when (state) {
            DeviceSessionState.STARTED -> scope.launch { attachCapabilities(created) }
            DeviceSessionState.STOPPED -> releaseCapabilities()
            else -> Unit
        }
    }

    private suspend fun attachCapabilities(created: DeviceSession) {
        if (camera != null) return
        if (!ensureCameraPermission()) return
        val micAllowed = ensureMicPermission()
        val cam = GlassesCamera(
            session = created,
            scope = scope,
            audioDir = File(context.filesDir, AUDIO_DIR),
            audioAllowed = micAllowed,
            onNote = ::note,
            onStreaming = { streaming -> _state.update { it.copy(streaming = streaming) } },
            onMicActive = { active -> _state.update { it.copy(micActive = active) } },
        )
        // Une caméra qui n'a pas démarré n'est pas retenue : elle bloquerait toute reprise.
        if (cam.start()) {
            camera = cam
            // La dictée peut prendre le micro des lunettes tant que le flux tourne.
            GlassesMicLink.attach { currentAudio() }
        }
        inputs = GlassesInputs(created, scope, clock, ::onGesture, ::note).also { it.start() }
    }

    /** Micro utilisable maintenant : flux en cours **et** micro autorisé, sinon rien. */
    private fun currentAudio(): PcmSource? {
        val snapshot = _state.value
        val usable = GlassesAudioPlan.usable(snapshot.connected, snapshot.streaming, snapshot.micAllowed)
        return if (usable) camera?.audio else null
    }

    private suspend fun ensureCameraPermission(): Boolean {
        val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        val granted = status is PermissionStatus.Granted
        _state.update { it.copy(cameraAllowed = granted) }
        if (!granted) {
            note(GlassesMessages.cameraPermissionMissing())
        }
        return granted
    }

    /** Micro côté app Meta AI : refusé, la session continue mais sans piste audio. */
    private suspend fun ensureMicPermission(): Boolean {
        val status = Wearables.checkPermissionStatus(Permission.MICROPHONE).getOrNull()
        val granted = status is PermissionStatus.Granted
        _state.update { it.copy(micAllowed = granted) }
        if (!granted) note(GlassesMessages.micPermissionMissing())
        return granted
    }

    /** Action décidée par le buffer d'appuis (logique pure, testée en JVM). */
    private fun onGesture(action: CaptureAction) {
        when (action) {
            CaptureAction.PHOTO, CaptureAction.ASK -> capture(action)
            CaptureAction.LIVE -> note(GlassesMessages.liveNotOnGlasses())
        }
    }

    /** Prend une photo côté lunettes et la pousse à l'app, prête pour la segmentation. */
    fun capture(action: CaptureAction = CaptureAction.PHOTO) {
        val activeCamera = camera
        if (activeCamera == null) {
            note(GlassesMessages.noCamera())
            return
        }
        scope.launch {
            val bitmap = activeCamera.capture() ?: return@launch
            val file = writeJpegToCache(context, bitmap)
            _events.emit(GlassesEvent.PhotoTaken(file, action))
        }
    }

    fun disconnect() {
        releaseCapabilities()
        session?.stop()
        dropSession()
        _state.update { it.copy(phase = SessionPhase.IDLE, streaming = false) }
    }

    private fun releaseCapabilities() {
        // Plus de flux, donc plus de micro à prêter à la dictée.
        GlassesMicLink.detach()
        inputs?.stop()
        inputs = null
        camera?.stop()
        camera = null
        _state.update { it.copy(streaming = false, micActive = false) }
    }

    /** Lunettes simulées : démo sans matériel, et plan B si l'appairage coince. */
    fun startMockDevice() {
        initialize()
        if (!mock.start()) return note(GlassesMessages.mockUnavailable())
        _state.update {
            it.copy(mockActive = true, deviceName = it.deviceName ?: "Ray-Ban Meta (simulées)", registered = true)
        }
        note(GlassesMessages.mockReady())
    }

    fun mockPress(type: CapturePressType = CapturePressType.SHORT_PRESS) {
        if (!mock.press(type)) note(GlassesMessages.noMockPress())
    }

    fun stopMockDevice() {
        mock.stop()
        _state.update { it.copy(mockActive = false) }
    }

    /** Libère session, capacités et coroutines (activité détruite). */
    fun close() {
        releaseCapabilities()
        session?.stop()
        dropSession()
        mock.stop()
        scope.cancel()
    }

    /** Affiche le message (teinté par sa gravité) et le pousse aux abonnés. */
    private fun note(message: GlassesMessage) {
        _state.update { it.copy(message = message) }
        _events.tryEmit(GlassesEvent.Note(message))
    }
}

/** Phase du SDK → phase affichée. `PAUSED` reste « en cours », la session reprend. */
internal fun DeviceSessionState.toPhase(): SessionPhase = when (this) {
    DeviceSessionState.IDLE -> SessionPhase.IDLE
    DeviceSessionState.STARTING -> SessionPhase.STARTING
    DeviceSessionState.STARTED -> SessionPhase.STARTED
    DeviceSessionState.PAUSED -> SessionPhase.STARTING
    DeviceSessionState.STOPPING, DeviceSessionState.STOPPED -> SessionPhase.STOPPED
}