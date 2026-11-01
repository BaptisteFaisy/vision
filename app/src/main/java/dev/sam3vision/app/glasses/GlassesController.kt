package dev.sam3vision.app.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.PcmSource
import dev.sam3vision.app.speechClient
import dev.sam3vision.core.glasses.CaptureAction
import dev.sam3vision.core.settings.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** WAV des dictées prises avec le micro des lunettes, sous `filesDir`. */
private const val AUDIO_DIR = "glasses-audio"

/** Extra adb de recette : lunettes simulées + connexion (`am start --ez glasses_mock_connect true`). */
const val EXTRA_GLASSES_MOCK_CONNECT = "glasses_mock_connect"

/** Extra adb de recette (debug seul) : injecte une question comme si les lunettes l'avaient entendue. */
const val EXTRA_GLASSES_TALK = "glasses_talk"

/** Journal de la chaîne lunettes (recette : `logcat -s GlassesChain`). */
private const val TAG = "GlassesChain"

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
    /**
     * Collecte des frames vidéo (décodage H.265 + SAM/LLM + voix). **Éteinte par
     * défaut** : le décodeur n'a jamais vu de matériel. Voir `GlassesCamera`.
     */
    private val collectVideoFrames: Boolean = false,
    /** Réglages courants (clés LLM/SAM/TTS) : lus à chaque frame analysée. */
    private val frameSettings: () -> Settings = { Settings.Default },
    /** Ouvre la page d'autorisation de Meta AI (contrat du SDK, fil principal). */
    private val permissionRequester: (Permission) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(GlassesUiState())
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 8)

    /** Voix des descriptions collectées (`GlassesFrameVoice`, mêmes clients que le banc). */
    private val frameVoice = GlassesFrameVoice(context, frameSettings)

    val state: StateFlow<GlassesUiState> = _state
    val events: SharedFlow<GlassesEvent> = _events

    init {
        // La voix suit la session : chaque phrase relit la sortie des lunettes, et
        // la fermeture de session la rend au téléphone (voir [GlassesVoiceRoute]).
        scope.launch { _state.collect(GlassesVoiceRoute::follow) }
    }

    // Implémentation (Mock Device Kit) en `app/src/debug/` : la dépendance n'est pas
    // embarquée dans l'APK de release, d'où la fabrique réflexive [mockDevice].
    internal val mocks = GlassesMockControls(scope, clock, mockDevice(context), _state, ::note, ::initialize, ::connect)

    /** Signaux sonores dans les lunettes (l'écran du téléphone est en poche). */
    private val beeps = GlassesTalkBeeps()

    /** Question mains-libres : micro des lunettes, transcription, événements. */
    private val talkHost = GlassesTalkHost(
        scope = scope,
        audio = {
            val snapshot = _state.value
            GlassesAudioPlan.select(snapshot.connected, snapshot.streaming, snapshot.micAllowed, camera?.audio)
        },
        engine = ::speechClient,
        settings = frameSettings,
        beep = beeps::invoke,
        state = _state,
        events = _events,
        note = ::note,
    )

    /** « Hey Meta, ouvre iSee » : session puis écoute, sans toucher le téléphone. */
    private val voiceInvocations = GlassesVoiceInvocations(
        scope = scope,
        snapshot = { _state.value },
        connect = ::connect,
        listen = ::onAsk,
        busy = { talkHost.busy },
        onNote = ::note,
    )

    /** Écoute auto : armée à l'attache caméra, tirée au premier flux montant. */
    private val autoListen = GlassesAutoListen(talkHost::autoListen)

    /** Ouverture de session : attente du lien, création, un seul rejou (fichier dédié). */
    private val opener = GlassesSessionOpener(
        scope = scope,
        clock = clock,
        snapshot = { _state.value },
        idle = {
            _state.update { it.copy(phase = SessionPhase.IDLE) }
            voiceInvocations.disarm()
        },
        note = ::note,
        report = { error, phase, current -> reportSessionError(error, activity, ::note, phase, current) },
        onCreated = { created -> startSession(created) },
    )

    /** Attache caméra/bouton : un seul attachement à la fois. */
    private val attachMutex = Mutex()
    private var session: DeviceSession? = null
    private var sessionWatch: Job? = null
    private var camera: GlassesCamera? = null
    private var inputs: GlassesInputs? = null
    private var activity: Activity? = null
    private var started = false

    /** Autorisations : demande ouverte par l'activité, verdict appliqué ici. */
    private val permissions = GlassesPermissionGate(
        requester = permissionRequester,
        hasActivity = { activity != null },
        update = { change -> _state.update(change) },
        note = ::note,
        onCameraGranted = { session?.let { live -> scope.launch { attachCapabilities(live) } } },
    )

    /** Première paire connue du SDK, publiée en nom / batterie / portée. */
    private val devices = GlassesDeviceWatch(scope) { change -> _state.update(change) }

    /** Dernier enregistrement connu, pour ne dire la perte qu'une fois (transition). */
    private var wasRegistered = false

    /** `true` entre `unregister()` et l'état suivant : la perte est voulue, on n'alerte pas. */
    private var unregisterWanted = false

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
        devices.start()
        voiceInvocations.start()
    }

    /**
     * Suit l'enregistrement. Le flux d'erreurs est écouté aussi : sans lui, un enregistrement
     * refusé (app Meta AI absente, refus de l'utilisateur) ne disait rien du tout — l'écran
     * restait sur « non enregistrée » sans explication.
     */
    private fun watchRegistration() {
        scope.launch {
            Wearables.registrationState.collect { state ->
                onRegistrationState(state == RegistrationState.REGISTERED)
            }
        }
        scope.launch {
            Wearables.registrationErrorStream
                .filter { error -> registrationErrorWorthTelling(error) }
                .collect { error -> note(GlassesMessages.registrationFailed(error.description)) }
        }
    }

    /**
     * Enregistrement reçu du SDK : l'état suit, et **une perte se dit** (mise à jour de
     * l'app ou de Meta AI, réinstallation) — sinon l'écran affichait « non enregistrée »
     * sans cause, et la session grisée restait un mystère.
     */
    private fun onRegistrationState(registered: Boolean) {
        _state.update { it.copy(registered = registered) }
        registrationLostMessage(wasRegistered, registered, unregisterWanted)?.let(::note)
        wasRegistered = registered
        if (registered) unregisterWanted = false
    }

    /** Enregistre l'app auprès de l'app Meta AI : la confirmation se fait côté Meta AI. */
    fun register() {
        initialize()
        val host = activity ?: return note(GlassesMessages.noActivity())
        // L'app Meta AI peut être absente ou refuser l'ouverture : le SDK lève, il ne rend
        // pas de résultat (`startRegistration` est `void` en 1.0.1) — on ne laisse pas
        // l'exception emporter le fil principal, et l'écran dit ce qui s'est passé.
        runCatching { Wearables.startRegistration(host) }
            .onSuccess { note(GlassesMessages.registrationStarted()) }
            .onFailure { error ->
                note(GlassesMessages.registrationFailed(error.message ?: "app Meta AI injoignable"))
            }
    }

    fun unregister() {
        // Désenregistrement **voulu** : ne pas l'annoncer comme une perte subie.
        unregisterWanted = true
        activity?.let { Wearables.startUnregistration(it) }
    }

    /** Demande caméra ou micro : la question se pose dans Meta AI (contrat du SDK). */
    fun requestPermission(permission: Permission) {
        initialize()
        permissions.request(permission)
    }

    /** Verdict de Meta AI : l'état suit, et la caméra s'attache sans refaire la session. */
    fun onPermissionResult(permission: Permission, result: PermissionResult) =
        permissions.result(permission, result)

    /** Retour d'enregistrement ou d'invocation vocale depuis l'app Meta AI. */
    fun handleIntent(intent: Intent) {
        val host = activity ?: return
        // Les deux étapes peuvent échouer (demande illisible, identifiants refusés) : sans ces
        // `onFailure`, Meta AI revenait dans l'app et l'écran restait muet.
        Wearables.handleIntent(intent) { request ->
            request.continueRegistration(host)
                .onFailure { error, _ -> note(registrationRequestErrorMessage(error)) }
        }.onFailure { error, _ -> note(registrationRequestErrorMessage(error)) }
    }

    fun connect() {
        initialize()
        // Une seule ouverture à la fois : un doublon faisait répondre `UNEXPECTED_ERROR`.
        if (_state.value.phase == SessionPhase.STARTING) {
            return note(GlassesMessages.sessionAlreadyRunning())
        }
        // Une session terminée (paire retirée, Bluetooth perdu) ne tient pas lieu de session :
        // sans cet oubli, le bouton restait grisé et l'utilisateur n'avait plus aucune prise.
        if (session != null && !_state.value.phase.allowsConnect()) {
            return note(GlassesMessages.sessionAlreadyRunning())
        }
        dropSession()
        _state.update { it.copy(phase = SessionPhase.STARTING) }
        opener.open()
    }

    private fun startSession(created: DeviceSession) {
        if (_state.value.phase != SessionPhase.STARTING) {
            // Arrêt demandé pendant la création : la session SDK ne démarre pas.
            runCatching { created.stop() }
            return
        }
        session = created
        // Les deux collecteurs meurent avec la session, sinon une session passée
        // réécrit l'état de la nouvelle (et chaque reconnexion en laissait deux de plus).
        sessionWatch = scope.launch {
            launch { created.state.collect { state -> onSessionState(created, state) } }
            launch {
                // Session oubliée : ses erreurs tardives n'écrasent pas la nouvelle.
                created.errors.collect { error -> if (created === session) reportSessionError(error, activity, ::note, _state.value.phase, _state.value.message) }
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
        Log.i(TAG, "session : $state")
        _state.update { it.copy(phase = state.toPhase()) }
        when (state) {
            DeviceSessionState.STARTED -> scope.launch { attachCapabilities(created) }
            DeviceSessionState.STOPPED -> releaseCapabilities()
            else -> Unit
        }
    }

    private suspend fun attachCapabilities(created: DeviceSession) {
        if (!attachMutex.tryLock()) {
            Log.i(TAG, "attache déjà en cours, doublon ignoré")
            return
        }
        try {
            attachLocked(created)
        } finally {
            attachMutex.unlock()
        }
    }

    private suspend fun attachLocked(created: DeviceSession) {
        if (camera != null) return
        if (!ensureCameraPermission()) return
        val micAllowed = ensureMicPermission()
        // Une vidéo par session : elle démarre à la première image du flux et se publie
        // dans la galerie à la fermeture. Aucun ré-encodage : le H.265 est muxé tel quel.
        val recorder = GlassesVideoRecorder(
            context = context,
            onNote = ::note,
            onRecording = { active -> _state.update { it.copy(recordingName = active) } },
        )
        val cam = GlassesCamera(
            session = created,
            scope = scope,
            audioDir = File(context.filesDir, AUDIO_DIR),
            audioAllowed = micAllowed,
            onNote = ::note,
            onStreaming = { streaming ->
                Log.i(TAG, "flux lunettes streaming=$streaming")
                _state.update { it.copy(streaming = streaming) }
                GlassesVideoLink.setStreaming(streaming)
                voiceInvocations.onStreaming(streaming)
                autoListen.onStreaming(streaming)
            },
            onMicActive = { active -> _state.update { it.copy(micActive = active) } },
            collectFrames = collectVideoFrames,
            onFrame = frameVoice::onFrame,
            onFrameLog = frameVoice::frameLog,
            recorder = recorder,
        )
        // Une caméra qui n'a pas démarré n'est pas retenue : elle bloquerait toute reprise.
        val started = cam.start()
        Log.i(TAG, "caméra lunettes démarrée : $started")
        if (started) {
            camera = cam
            // La dictée peut prendre le micro des lunettes tant que le flux tourne.
            GlassesMicLink.attach {
                val snapshot = _state.value
                GlassesAudioPlan.select(snapshot.connected, snapshot.streaming, snapshot.micAllowed, camera?.audio)
            }
            GlassesVideoLink.attachNotes(::note)
            // Session ouverte : l'écoute part au premier flux montant.
            autoListen.arm()
        }
        inputs = GlassesInputs(created, scope, clock, ::onGesture, ::note).also { it.start() }
        GlassesVideoLink.attachHeadPose(scope, ::note, created)
    }

    private suspend fun ensureCameraPermission(): Boolean {
        val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        if (status == null) {
            // Lecture impossible (lien instable) : on garde l'état connu au lieu de révoquer
            // à tort — un vrai refus se dira à l'attache (`CAPABILITY_DENIED`).
            Log.w(TAG, "permission caméra illisible, état gardé")
            if (!_state.value.cameraAllowed) note(GlassesMessages.cameraPermissionMissing())
            return _state.value.cameraAllowed
        }
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
        if (status == null) {
            // Lecture impossible (lien instable) : on garde l'état connu au lieu de révoquer
            // à tort — un vrai refus se dira à l'attache (palier audio).
            Log.w(TAG, "permission micro illisible, état gardé")
            if (!_state.value.micAllowed) note(GlassesMessages.micPermissionMissing())
            return _state.value.micAllowed
        }
        val granted = status is PermissionStatus.Granted
        _state.update { it.copy(micAllowed = granted) }
        if (!granted) note(GlassesMessages.micPermissionMissing())
        return granted
    }

    /** Action décidée par le buffer d'appuis (logique pure, testée en JVM). */
    private fun onGesture(action: CaptureAction) {
        when (action) {
            CaptureAction.PHOTO -> capture(action)
            CaptureAction.ASK -> onAsk()
            CaptureAction.LIVE -> note(GlassesMessages.liveNotOnGlasses())
        }
    }

    /** Prend une photo côté lunettes et la pousse à l'app, prête pour la segmentation. */
    fun capture(action: CaptureAction = CaptureAction.PHOTO) {
        if (camera == null) {
            note(GlassesMessages.noCamera())
            return
        }
        scope.launch {
            val file = snapPhoto() ?: return@launch
            _events.emit(GlassesEvent.PhotoTaken(file, action))
        }
    }

    /** Photo des lunettes écrite dans le cache, sans l'envoyer (`null` = ratée). */
    private suspend fun snapPhoto(): File? {
        val bitmap = camera?.capture() ?: return null
        return writeJpegToCache(context, bitmap)
    }

    /** Appui long : question mains-libres (écoute seule), ou envoi si elle est ouverte. */
    private fun onAsk() {
        // Micro des lunettes inutilisable : dit, sans photo.
        if (talkHost.press() == TalkPress.MIC_UNAVAILABLE) note(GlassesMessages.talkMicUnavailable())
    }

    /** « Hey Meta, ouvre iSee » (flux ou intent) : session puis écoute. */
    fun onVoiceLaunch() = voiceInvocations.onLaunch()

    /**
     * Recette sans voix (debug seul, `--es glasses_talk "question"`) : injecte
     * une question comme si le micro des lunettes l'avait entendue — le mock
     * n'ayant pas de piste audio, c'est le seul chemin qui prouve l'aval.
     */
    fun debugTalkTranscript(text: String) {
        if (!BuildConfig.DEBUG || text.isBlank()) return
        Log.i(TAG, "recette : question injectée")
        talkHost.inject(text)
    }

    fun disconnect() {
        // L'arrêt d'abord : une ouverture en cours s'interrompt au lieu d'aboutir.
        opener.cancel()
        _state.update { it.copy(phase = SessionPhase.IDLE, streaming = false) }
        releaseCapabilities()
        session?.stop()
        dropSession()
    }

    private fun releaseCapabilities() {
        // Plus de flux, donc plus de micro à prêter à la dictée, ni d'image à l'accueil.
        talkHost.cancel()
        voiceInvocations.disarm()
        autoListen.disarm()
        GlassesMicLink.detach()
        GlassesVideoLink.detachNotes()
        GlassesVideoLink.clear()
        inputs?.stop()
        inputs = null
        camera?.stop()
        camera = null
        frameVoice.stop()
        _state.update { it.copy(streaming = false, micActive = false, recordingName = null, talkListening = false) }
    }

    /** Libère session, capacités et coroutines (activité détruite). */
    fun close() {
        opener.cancel()
        releaseCapabilities()
        session?.stop()
        dropSession()
        mocks.stop()
        voiceInvocations.stop()
        frameVoice.release()
        beeps.release()
        GlassesVoiceRoute.forget()
        scope.cancel()
    }

    /** Affiche le message (teinté par sa gravité) et le pousse aux abonnés. */
    private fun note(message: GlassesMessage) {
        Log.i(TAG, "note ${message.kind} : ${message.text}")
        _state.update { it.copy(message = message) }
        _events.tryEmit(GlassesEvent.Note(message))
    }
}
