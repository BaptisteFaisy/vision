package dev.sam3vision.app

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.isee.MaskPainter
import dev.sam3vision.app.isee.pictosDir
import dev.sam3vision.app.live.CenterTrails
import dev.sam3vision.app.live.LiveAskSession
import dev.sam3vision.app.live.LiveFrameDispatcher
import dev.sam3vision.app.live.LiveFpsMeter
import dev.sam3vision.app.live.LiveNarration
import dev.sam3vision.app.live.LivePrediction
import dev.sam3vision.app.live.chosenLens
import dev.sam3vision.app.live.LivePositionVoice
import dev.sam3vision.app.live.LiveTrackFallback
import dev.sam3vision.app.live.LiveTransportLog
import dev.sam3vision.app.live.LiveWakeGuard
import dev.sam3vision.app.traverse.TraverseWiring
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.FramePipeline
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.TrackRunner
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.live.ScreenTimings
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.net.HttpTrackClient
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.traverse.TraversePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestration du direct : une frame à la fois, masques recolorés, stats, mémoire [LiveTimeline]. Briques `live/` testables en JVM, API inchangée.
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStoreFactory.create(app)

    /** Dernière frame JPEG : hors du state (voir [LatestFrame]), lue par les questions. */
    internal val latestFrame = LatestFrame()

    internal val _state = MutableStateFlow(LiveUiState(latestFrame = latestFrame))
    val state: StateFlow<LiveUiState> = _state
    private var session: LiveSession? = null
    private var client: LiveClient? = null

    /** Session du tracker (`/session` + `/frame`), `null` quand le direct est sur `/live`. */
    private var tracker: TrackRunner? = null

    /** Tracker à ids stables par défaut (~30 ms serveur) ; `/live` WS en repli via le bandeau. */
    private var trackerEnabled = true

    /** Premier plan : en arrière-plan les frames sont jetées (la carte reste éteinte). */
    private val foregroundGate = ForegroundTracker()

    private val trackFallback = LiveTrackFallback()

    /** Pipeline actif, tout transport : cadence, époque, latence — l'écran ignore le client. */
    internal val pipeline: FramePipeline? get() = tracker ?: session

    /** Dernière frame gardée pour les questions (indépendant de SAM). */
    private var lastKeepAt = 0L

    /** Chronologie de la frame courante (ns monotones, même base que `imageInfo.timestamp`). */
    internal val chronology = ScreenTimings { SystemClock.elapsedRealtimeNanos() }

    /** Journal du direct : 60 s d'objets suivis, identités stables. */
    private val timeline = LiveTimeline()

    /** Distances caméra→objets : sténopé + hauteurs connues, lissées par piste. */
    private val distances = DistanceMeter()

    /** Chemins centraux des objets suivis : le centre de chaque boîte allonge sa piste. */
    private val centerTrails = CenterTrails()

    /**
     * Prédiction locale des boîtes (T8) : remplacée à chaque démarrage, car c'est le
     * réglage [Settings.boxPredictionEnabled] — **éteint par défaut** — qui décide.
     * Éteinte, la classe ci-dessus n'entre dans aucun chemin : le direct est celui d'avant.
     */
    private var prediction = LivePrediction(enabled = false)
    private var sensorLens: LensSpec? = null

    private var settings: Settings = Settings.Default

    /** Fenêtre d'attente du premier réveil : au-delà, une frame en échec est une erreur. */
    private val wake = LiveWakeGuard()

    /** Ips sur l'intervalle réel entre deux frames publiées, trous rejetés. */
    private val fps = LiveFpsMeter()

    /** Envois du Direct : pompe `/live` (continue + réserve) ou cadence tracker. */
    private val dispatcher = LiveFrameDispatcher(
        viewModelScope,
        Dispatchers.Default,
        { session },
        { _state.value.prompts },
        { jpeg, at -> chronology.beginFrame(at); keepFrame(jpeg) },
        { active, era, result -> handleResult(active, era, result) },
    )
    val local = dev.sam3vision.app.live.LiveLocalOverlayWiring(_state, store) { settings.localOverlayEnabled }

    /** Masques coloriés sur [MaskPainter] : tampons réutilisés, même peintre que la sonde. */
    private val painter = MaskPainter({ maskColorAt(it).toArgb() }, MASK_ALPHA)

    /** Annonce vocale des positions : une phrase à la fois, jamais superposée. */
    private val voice = VoiceAnnouncer(app, POSITION_VOICE_DIR)

    private val positions = LivePositionVoice(
        scope = viewModelScope,
        state = _state,
        settings = { settings },
        asking = { _state.value.memory.asking || asker.pictoActive },
        speak = { current, phrase -> voice.say(current, phrase) },
    )

    /** Câblage traversée : pipeline + voix, branché dans [publish]. */
    private val traverse = TraverseWiring(TraversePipeline())

    /** Narration continue : une frame sur deux secondes part au LLM vision. */
    private val narration = LiveNarration(viewModelScope, _state) {
        _state.value.frame?.objects?.map { it.label }?.distinct().orEmpty()
    }

    /** Demandes sur le direct : questions LLM, chasses SAM, ordres picto. */
    internal val asker = LiveAskSession(
        scope = viewModelScope,
        state = _state,
        timeline = timeline,
        latestJpeg = { latestFrame.jpegB64 },
        refreshSettings = { settings = loadedSettings(store) },
        settings = { settings },
        llm = { llmClient(it) },
        windowMs = MEMORY_WINDOW_MS,
        restart = { restart(it) },
        pictoDir = { pictosDir(app.filesDir) },
        pictoSpeak = { current, phrase -> voice.say(current, phrase) },
    )

    /** Voix du direct : « Parler » pose la question sur le flux (mot maison seul = chasse SAM), sans clavier. */
    private val liveVoice = LiveVoice(
        app = app,
        scope = viewModelScope,
        settings = { settings },
        onText = { askMemory(it) },
        onState = { spoken -> _state.update { it.copy(voice = spoken) } },
    )

    /** Taille de la dernière frame bien dimensionnée (repli si le backend l'omet). */
    private var lastKnownWidth = 0
    private var lastKnownHeight = 0

    /**
     * Ouvre une session de direct. Par défaut (`liveSamEnabled = false`), l'image part
     * **directement au LLM** : aucun client HTTP SAM, aucune session, aucun réveil GPU.
     * Avec `liveSamEnabled`, la session `/live` est ouverte et la première frame
     * réveille le GPU — aucun réveil avant de filmer.
     * Dans les deux cas, la clé LLM est indispensable.
     */
    fun start(settings: Settings, prompts: List<String> = LivePrompts.default) {
        this.settings = seededFromBuild(settings)
        foregroundGate.reset()
        if (!this.settings.llmConfigured) return run { _state.update { it.copy(error = LLM_ABSENT_LINE) } }
        resetForStart(prompts)
        LiveVoiceRequest.consume()?.let { asker.applyVoiceRequest(it) }
        liveNarration(this.settings)?.let { narration.open(it) }
        if (!this.settings.liveSamEnabled) {
            startVisionOnly()
            return
        }
        val base = this.settings.liveBaseUrl()
        if (base.isBlank()) {
            session = null
            client = null
            tracker = null
            _state.update { it.copy(running = true, starting = false, error = SAM_ABSENT_LINE) }
            return
        }
        // Réveil explicite (ordre user 2026-10-29) : le boot recouvre la liaison caméra ; la 1re frame reste le signal de prêt (`LiveWakeGuard` couvre l'attente).
        // Le tracker suit des ids stables ; `/live` WS est le défaut (knobs de transport des masques).
        session = null
        client = null
        tracker = null
        if (trackerEnabled) {
            tracker = TrackRunner(HttpTrackClient(base))
            LiveTransportLog.info("session track ouverte base=" + base)
        } else {
            val live = LiveMaskTransport.clientFor(base, marks = chronology)
            client = live
            session = LiveSession(live, prompts = _state.value.prompts, maxObjects = this.settings.objectsOnScreen)
        }
        LiveTransportLog.info("start transport=" + if (trackerEnabled) TRANSPORT_TRACK else TRANSPORT_WS)
        _state.update {
            it.copy(
                running = true,
                starting = true,
                error = null,
                memory = LiveMemoryState(),
                spoken = null,
                transport = if (trackerEnabled) TRANSPORT_TRACK else TRANSPORT_WS,
            )
        }
        wake.arm().also { viewModelScope.launch { GpuWake.wake(base) } }
    }

    /** Direct en vision LLM seule : ni session, ni client SAM, ni réveil GPU — juste l'image au LLM. */
    private fun startVisionOnly() {
        session = null
        client = null
        tracker = null
        _state.update {
            it.copy(running = true, starting = false, error = null, gpu = "", spoken = null)
        }
    }

    /** Remise à zéro commune aux deux démarrages : journal, distances, cadence, concepts. */
    private fun resetForStart(prompts: List<String>) {
        trackFallback.reset()
        traverse.reset()
        val chosen = LivePrompts.normalize(prompts)
        // Journal et historique : vidés par `asker.reset()` (implémentation unique, partagée avec `stop()`).
        asker.reset()
        centerTrails.reset()
        distances.enabled = settings.distanceOverlayEnabled
        distances.reset()
        prediction = LivePrediction(enabled = settings.boxPredictionEnabled)
        distances.setLens(chosenLens(settings.cameraFovDeg, sensorLens))
        wake.reset()
        dispatcher.reset(settings.continuousCadenceEnabled)
        fps.reset()
        lastKeepAt = 0L
        _state.update {
            it.copy(prompts = chosen, samEnabled = settings.liveSamEnabled, trackerEnabled = trackerEnabled, traverseActive = traverse.active)
        }
    }

    /** Change les concepts suivis : `[promptsText]` séparé par virgules, vide = défaut. */
    fun restartWith(promptsText: String) {
        restart(LivePrompts.field(promptsText))
    }

    /** Relance la session avec les concepts déjà actifs (flux arrêté ou en erreur). */
    fun restartActive() = restart(_state.value.prompts.ifEmpty { LivePrompts.default })

    /**
     * Bascule « Vision seule ↔ SAM » depuis le bandeau du direct : le réglage est
     * persisté, puis la session est relancée dans le nouveau mode.
     */
    fun toggleSam() {
        viewModelScope.launch {
            val next = store.load().let { it.copy(liveSamEnabled = !it.liveSamEnabled) }
            store.save(next)
            start(next, _state.value.prompts.ifEmpty { LivePrompts.default })
        }
    }

    private fun restart(prompts: List<String>) = viewModelScope.launch { start(store.load(), prompts) }

    /** Bascule WS ↔ tracker depuis le bandeau (choix non persisté, séance relancée). */
    fun toggleTracker() {
        trackerEnabled = !trackerEnabled
        restartActive()
    }

    /** Bascule le mode traversée : prompts véhicules + voix, ou retour au défaut. */
    fun setTraverseActive(active: Boolean) {
        traverse.setActive(active, getApplication(), settings, viewModelScope, _state.value.prompts, { restart(it) }, positions::reset)
        _state.update { it.copy(traverseActive = traverse.active) }
    }

    /**
     * Frame venue de l'analyseur caméra. Sans session SAM, elle est seulement **gardée
     * pour les questions** — les questions marchent donc même quand SAM est injoignable.
     * Sinon elle part au GPU (elle réveille le conteneur) : `/live` continu, tracker adaptatif.
     */
    fun wantsFrame(): Boolean = foregroundGate.foreground && (local.wantsLuma() || traverse.active || pipeline == null || dispatcher.wantsFrame(pipeline, System.currentTimeMillis())) // porte d'encodage, motif iSee : un consommateur veut-il la frame ?
    fun onFrame(jpegB64: String, captureAtNs: Long = 0L, frameLuma: ByteArray? = null) {
        if (!foregroundGate.foreground || jpegB64.isBlank()) return
        narration.narrate(jpegB64)
        publishPrediction()
        local.tick(frameLuma, System.currentTimeMillis())
        val active = pipeline
        if (active == null) {
            keepFrame(jpegB64)
            return
        }
        if (traverse.active) { traverse.offerLightFrame(jpegB64); _state.update { it.withCrossing(true, traverse.local ?: traverse.light) } }
        dispatcher.onFrame(active, jpegB64, captureAtNs, System.currentTimeMillis())
    }

    /**
     * Réponse du pipeline : périmée (arrêt, nouveaux concepts) = rien n'est publié,
     * sinon accusé de réception puis publication ou échec ([publishFailure]).
     */
    private suspend fun handleResult(active: FramePipeline, era: Long, result: Result<LiveFrame>?) {
        if (!active.isCurrent(era)) return
        chronology.markReceived()
        if (result == null) {
            publishStages(null)
            return
        }
        result.fold(
            onSuccess = { frame -> publish(frame) },
            onFailure = { error -> publishFailure(active, error) },
        )
    }

    /** Échec : repli `/live` au démarrage (vrai = relancé), sinon erreur hors réveil. */
    private fun publishFailure(active: FramePipeline, error: Throwable): Boolean {
        publishStages(null)
        // Tracker muet au démarrage : repli `/live` unique (pas « injoignable »).
        if (trackFallback.shouldFallBack(_state.value.starting, active is TrackRunner)) {
            trackerEnabled = false
            LiveTransportLog.info("repli tracker->/live raison=" + (error.message ?: "GPU injoignable"))
            restartActive()
            return true
        }
        LiveTransportLog.failure("frame echec transport=" + transportLabel(trackerEnabled, client) + " " + (error.message ?: "GPU injoignable"))
        onFrameError(error)
        return false
    }

    /**
     * Échec réseau d'une frame (voir [frameErrorState] : réveil GPU = erreur masquée) ;
     * l'envoi, lui, passe par [LiveFrameDispatcher] (tous les transports).
     */
    private fun onFrameError(error: Throwable) {
        val waking = _state.value.starting && wake.isWaking()
        _state.update { frameErrorState(it, waking, transportLabel(trackerEnabled, client), error.message) }
    }

    /** Garde la frame courante pour les questions : au plus une par [FRAME_KEEP_MS]. */
    private fun keepFrame(jpegB64: String) {
        val now = System.currentTimeMillis()
        if (!shouldKeepFrame(now, lastKeepAt)) return
        lastKeepAt = now
        latestFrame.set(jpegB64)
        // Une seule émission porte le porteur ; « Analyser » lit `hasFrame` (pas de recopie 28 Ko).
        if (!_state.value.hasFrame) _state.update { it.copy(latestFrame = latestFrame) }
    }

    /** Masques coloriés hors thread principal ; poste `overlays` posé juste après. */
    private suspend fun decodeOverlays(frame: LiveFrame): List<Bitmap> =
        withContext(Dispatchers.Default) {
            val startedAt = SystemClock.elapsedRealtime()
            val overlays = painter.overlays(frame)
            chronology.markOverlays()
            painter.noteDecoded(SystemClock.elapsedRealtime() - startedAt, overlays.size)
            overlays
        }

    /** Publie la frame analysée : masques, statistiques et journal des objets vus. */
    private suspend fun publish(frame: LiveFrame) {
        val now = System.currentTimeMillis()
        val width = frame.width.takeIf { it > 0 } ?: lastKnownWidth
        val height = frame.height.takeIf { it > 0 } ?: lastKnownHeight
        if (width > 0) {
            lastKnownWidth = width
            lastKnownHeight = height
        }
        val shown = traverse.filter(frame, width, height, distances.lensSpec.focalPx(width, height), now, settings.speechLanguage, latestFrame.jpegB64)
        val seen = timeline.record(now, shown.detections)
        // Réponse serveur absorbée par la prédiction : elle sert d'ancre aux boîtes
        // extrapolées entre deux inférences. Flag éteint : appel inerte (`null`).
        prediction.absorb(shown.frame, now, width, height)
        val readings = distances.measure(width, height, shown.detections, seen.map { it?.id })
        if (!traverse.active || shown.keepGuidance) positions.announce(shown.frame, width, height, now, readings)
        if (!traverse.active) asker.onLiveFrame(width, height, now)
        // Calcul hors thread principal ; le poste `overlays` est posé juste après.
        val overlays = decodeOverlays(shown.frame)
        val predicted = prediction.boxesAt(now, width, height)
        val gap = fps.gapAt(now)
        val recent = timeline.recent(now, MEMORY_WINDOW_MS)
        publishStages(shown.frame)
        _state.update { current ->
            current.copy(
                frame = shown.frame,
                overlays = overlays,
                objectsCount = shown.frame.objects.size,
                transport = transportLabel(trackerEnabled, client),
                latencyMs = pipeline?.stats?.averageMs ?: 0L,
                fps = LiveFpsMeter.fpsFor(gap, current.fps),
                error = null,
                starting = false,
                memory = current.memory.copy(tracks = recent.tracks),
                distanceLayer = if (readings.isEmpty()) null else DistanceLayer(width, height, readings, distances.lensSpec.widthFovDeg(width, height)),
                prediction = predicted,
                trails = centerTrails.observeFrame(now, width, height, shown.detections, seen).takeUnless { it.empty },
            ).withCrossing(traverse.active, traverse.local ?: traverse.light)
        }
    }

    /** Boîtes prédites de la frame caméra, posées si la couche a changé. */
    private fun publishPrediction() {
        val layer = prediction.publishableLayer(System.currentTimeMillis(), lastKnownWidth, lastKnownHeight, _state.value.prediction) ?: return
        _state.update { it.copy(prediction = layer) }
    }

    /** Instantané de la chronologie dans l'état : l'UI ne lit jamais les marques. */
    internal fun publishStages(frame: LiveFrame?) =
        _state.update { it.copy(timings = chronology.snapshot(frame)) }

    internal fun publishDrawnStages() =
        _state.update { current -> current.copy(timings = chronology.snapshot(current.timings)) }

    /**
     * Calibrage des distances dès que la caméra est liée : le champ de vision réglé
     * à la main prime, sinon celui lu sur le capteur, sinon le grand-angle de repli.
     */
    fun applyLens(spec: LensSpec?) {
        if (spec != null && spec.usable) sensorLens = spec
        distances.setLens(chosenLens(settings.cameraFovDeg, sensorLens))
    }

    /**
     * Question sur le direct : la **dernière image part tout de suite au LLM**, sans
     * dépendre de SAM (ni session, ni journal, ni GPU). L'historique est conservé.
     * Les réglages sont relus à chaque question : une clé saisie pendant le direct
     * s'applique tout de suite.
     */
    fun askMemory(question: String) {
        asker.ask(question)
    }

    /** Bouton « Parler » du panneau : le micro s'ouvre pour poser la question à la voix. */
    fun startVoice() = liveVoice.start()

    /** Bouton « Stop » : fin de la parole, transcription puis question automatique. */
    fun stopVoice() = liveVoice.stop()

    /** Quitte le premier plan : flux SAM coupé (`POST /release` reste dans `AppViewModel.releaseGpu`). */
    fun onBackground() {
        val wasRunning = _state.value.running
        foregroundGate.onBackground(wasRunning)
        if (!wasRunning) return
        pipeline?.reset()
        val closing = tracker
        session = null
        tracker = null
        client?.close()
        client = null
        viewModelScope.launch { closing?.stop() }
        _state.update { it.copy(running = false, starting = false) }
    }

    /** Retour au premier plan : le direct coupé par [onBackground] repart tout seul. */
    fun onForeground() {
        if (foregroundGate.onForeground()) startFromSettings()
    }

    /** Bouton « Stop » : vol révoqué, mémoire effacée — un direct arrêté ne répond plus. */
    fun stop() {
        foregroundGate.reset()
        narration.close()
        liveVoice.cancel()
        // Frame en vol révoquée (époque close) : réponse tardive jetée, état « arrêté » intact.
        pipeline?.reset()
        val closing = tracker
        session = null
        tracker = null
        client?.close()
        client = null
        // Fermeture de session côté backend, sans bloquer l'arrêt de l'écran.
        LiveTransportLog.info("stop session track fermee tracker=" + (closing != null))
        viewModelScope.launch { closing?.stop() }
        distances.reset()
        prediction.reset()
        wake.reset()
        traverse.deactivate(positions::reset)
        voice.stop()
        latestFrame.clear()
        chronology.reset()
        asker.reset()
        _state.update { LiveUiState(latestFrame = latestFrame) }
    }

    override fun onCleared() {
        client?.close()
        voice.release()
        traverse.close()
        super.onCleared()
    }

    /** Lit les réglages chiffrés puis démarre la session (appelé à l'entrée à l'écran). */
    fun startFromSettings(prompts: List<String> = LivePrompts.default) = viewModelScope.launch { start(store.load(), prompts) }
}