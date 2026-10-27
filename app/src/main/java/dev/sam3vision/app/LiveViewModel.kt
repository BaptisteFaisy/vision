package dev.sam3vision.app

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.live.LiveAskSession
import dev.sam3vision.app.live.LiveFrameCadence
import dev.sam3vision.app.live.LiveFpsMeter
import dev.sam3vision.app.live.LiveMaskDecoder
import dev.sam3vision.app.live.LiveNarration
import dev.sam3vision.app.live.LivePrediction
import dev.sam3vision.app.live.chosenLens
import dev.sam3vision.app.live.runAsFrame
import dev.sam3vision.app.live.LivePositionVoice
import dev.sam3vision.app.live.LiveTrackFallback
import dev.sam3vision.app.live.LiveTransportLog
import dev.sam3vision.app.live.LiveWakeGuard
import dev.sam3vision.app.live.MaskPainter
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.FramePipeline
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.TrackRunner
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.live.ScreenTimings
import dev.sam3vision.core.mask.colorizedObjectBitmap
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.net.HttpTrackClient
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /** Une frame en vol maximum, cadence adaptative de la session. */
    private val cadence = LiveFrameCadence()
    val local = dev.sam3vision.app.live.LiveLocalOverlayWiring(_state, store) { settings.localOverlayEnabled }

    /**
     * Masques coloriés hors thread principal ; le poste `overlays` est posé au retour.
     * Le décodage se fait **par objet** ([colorizedObjectBitmap]) : `rle` et `poly` arrivent
     * sur la grille réduite annoncée par `mask_scale` et sont agrandis avant coloriage.
     */
    private val masks: LiveMaskDecoder<Bitmap> = LiveMaskDecoder(
        paint = MaskPainter { obj, frameWidth, frameHeight, argb, alpha ->
            colorizedObjectBitmap(obj, frameWidth, frameHeight, argb, alpha)
        },
        colorAt = { maskColorAt(it).toArgb() },
        alpha = MASK_ALPHA,
        onDecoded = { chronology.markOverlays() },
    )

    /** Annonce vocale des positions : une phrase à la fois, jamais superposée. */
    private val voice = VoiceAnnouncer(app, POSITION_VOICE_DIR)

    private val positions = LivePositionVoice(
        scope = viewModelScope,
        state = _state,
        settings = { settings },
        asking = { _state.value.memory.asking },
        speak = { current, phrase -> voice.say(current, phrase) },
    )

    /** Narration continue : une frame sur deux secondes part au LLM vision. */
    private val narration = LiveNarration(viewModelScope, _state) {
        _state.value.frame?.objects?.map { it.label }?.distinct().orEmpty()
    }

    /** Questions sur le direct : dernière image + journal, historique conservé. */
    private val asker = LiveAskSession(
        scope = viewModelScope,
        state = _state,
        timeline = timeline,
        latestJpeg = { latestFrame.jpegB64 },
        refreshSettings = { settings = seededFromBuild(store.load()) },
        settings = { settings },
        llm = { llmClient(it) },
        windowMs = MEMORY_WINDOW_MS,
    )

    /** Voix du direct : « Parler » pose la question sur le flux, sans clavier. */
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
     * Avec `liveSamEnabled`, la session `/live` est ouverte et le GPU réveillé comme avant.
     * Dans les deux cas, la clé LLM est indispensable.
     */
    fun start(settings: Settings, prompts: List<String> = LivePrompts.Default) {
        this.settings = seededFromBuild(settings)
        foregroundGate.reset()
        if (!this.settings.llmConfigured) {
            _state.update { it.copy(error = LLM_ABSENT_LINE) }
            return
        }
        resetForStart(prompts)
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
        // Deux transports, aucun `/health` : la 1re frame réveille le conteneur (file Modal, 8-35 s).
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
        wake.arm()
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
        val chosen = LivePrompts.normalize(prompts)
        // Journal et historique de questions : vidés par `asker.reset()` (une seule
        // implémentation de « la mémoire du direct s'efface », partagée avec `stop()`).
        asker.reset()
        distances.enabled = settings.distanceOverlayEnabled
        distances.reset()
        prediction = LivePrediction(enabled = settings.boxPredictionEnabled)
        distances.setLens(chosenLens(settings.cameraFovDeg, sensorLens))
        wake.reset()
        cadence.reset()
        fps.reset()
        lastKeepAt = 0L
        _state.update {
            it.copy(prompts = chosen, samEnabled = settings.liveSamEnabled, trackerEnabled = trackerEnabled)
        }
    }

    /** Change les concepts suivis : `[promptsText]` séparé par virgules, vide = défaut. */
    fun restartWith(promptsText: String) {
        restart(LivePrompts.field(promptsText))
    }

    /** Relance la session avec les concepts déjà actifs (flux arrêté ou en erreur). */
    fun restartActive() = restart(_state.value.prompts.ifEmpty { LivePrompts.Default })

    /**
     * Bascule « Vision seule ↔ SAM » depuis le bandeau du direct : le réglage est
     * persisté, puis la session est relancée dans le nouveau mode.
     */
    fun toggleSam() {
        viewModelScope.launch {
            val next = store.load().let { it.copy(liveSamEnabled = !it.liveSamEnabled) }
            store.save(next)
            start(next, _state.value.prompts.ifEmpty { LivePrompts.Default })
        }
    }

    private fun restart(prompts: List<String>) = viewModelScope.launch { start(store.load(), prompts) }

    /** Bascule WS ↔ tracker depuis le bandeau (choix non persisté, séance relancée). */
    fun toggleTracker() {
        trackerEnabled = !trackerEnabled
        restartActive()
    }

    /**
     * Frame venue de l'analyseur caméra. Sans session SAM, elle est seulement **gardée
     * pour les questions** — les questions marchent donc même quand SAM est injoignable.
     * Sinon elle part au GPU (c'est elle qui réveille le conteneur endormi), sauf si une
     * requête est en vol ou si la cadence adaptative n'est pas écoulée (le lien montant
     * sature sinon).
     */
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
        if (!cadence.admits(active, System.currentTimeMillis())) return
        // Nouvelle frame : le JPEG vient d'être produit, la chronologie repart de là.
        chronology.beginFrame(captureAtNs)
        keepFrame(jpegB64)
        // Époque de la séance au départ de la frame : si un arrêt (ou un changement de
        // concepts) survient pendant l'aller-retour, la réponse est périmée et rien ne
        // doit être publié — sinon l'écran d'un direct arrêté se rallume tout seul.
        val era = active.generation
        // Dispatchers.Default : `viewModelScope` est Main.immediate, et le corps de la
        // requête (normalisation des concepts, JSON, base64 de 28 Ko) partirait sur la
        // boucle de composition. Ce travail n'a rien à y faire.
        viewModelScope.launch(Dispatchers.Default) {
            val result = active.sendFrame(_state.value.prompts, jpegB64)
            if (!active.isCurrent(era)) return@launch
            chronology.markReceived()
            if (result == null) {
                publishStages(null)
                return@launch
            }
            result.fold(
                onSuccess = { frame -> publish(frame) },
                onFailure = { error -> if (publishFailure(active, error)) return@launch },
            )
        }
    }

    /** Échec : repli `/live` au démarrage (vrai = relancé), sinon erreur hors réveil. */
    private fun publishFailure(active: FramePipeline, error: Throwable): Boolean {
        publishStages(null)
        // Tracker muet au démarrage (backend non déployé) : on relance en `/live`
        // plutôt que d'afficher « injoignable ». Une seule fois par séance.
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
     * l'envoi de la frame, lui, passe par [FramePipeline.sendFrame] (tout transport).
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
        // Une seule émission d'état porte le porteur : ensuite « Analyser la frame » se
        // contente de lire `hasFrame`, sans recopier 28 Ko à chaque frame.
        if (!_state.value.hasFrame) _state.update { it.copy(latestFrame = latestFrame) }
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
        val detections = frame.objects.toDetections(width, height)
        val seen = timeline.record(now, detections)
        // Réponse serveur absorbée par la prédiction : elle sert d'ancre aux boîtes
        // extrapolées entre deux inférences. Flag éteint : appel inerte (`null`).
        prediction.absorb(frame, now, width, height)
        val readings = distances.measure(width, height, detections, seen.map { it?.id })
        positions.announce(frame, width, height, now, readings)
        // Calcul hors thread principal (voir LiveMaskDecoder) ; le poste `overlays` est posé là-bas.
        val overlays = masks.decode(frame)
        val predicted = prediction.boxesAt(now, width, height)
        val gap = fps.gapAt(now)
        val recent = timeline.recent(now, MEMORY_WINDOW_MS)
        publishStages(frame)
        _state.update { current ->
            current.copy(
                frame = frame,
                overlays = overlays,
                objectsCount = frame.objects.size,
                transport = transportLabel(trackerEnabled, client),
                latencyMs = pipeline?.stats?.averageMs ?: 0L,
                fps = LiveFpsMeter.fpsFor(gap, current.fps),
                error = null,
                starting = false,
                memory = current.memory.copy(tracks = recent.tracks),
                distanceLayer = if (readings.isEmpty()) null else DistanceLayer(width, height, readings, distances.lensSpec.widthFovDeg(width, height)),
                prediction = predicted,
            )
        }
    }

    /**
     * Boîtes prédites de la frame caméra, posées une fois par frame (30 fois par
     * seconde) : c'est ce qui fait avancer l'overlay **entre** deux réponses serveur.
     * Flag éteint ou rien de périmé : aucune écriture d'état, aucun coût.
     */
    private fun publishPrediction() {
        if (!prediction.enabled) return
        val layer = prediction.boxesAt(System.currentTimeMillis(), lastKnownWidth, lastKnownHeight)
        if (layer.empty && _state.value.prediction.empty) return
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

    /** Instant demandé dans l'UI : « il y a N s » (0 = maintenant). */
    fun setFocus(seconds: Int) = _state.update {
        it.copy(memory = it.memory.copy(focusSeconds = seconds.coerceAtLeast(0)))
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
        // La frame en vol est révoquée (époque close) : sa réponse tardive est jetée, elle
        // ne réécrit pas l'état « arrêté » et ne vole pas le créneau de la séance suivante.
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
        positions.reset()
        voice.stop()
        latestFrame.clear()
        chronology.reset()
        asker.reset()
        _state.update { LiveUiState(latestFrame = latestFrame) }
    }

    override fun onCleared() {
        client?.close()
        voice.release()
        super.onCleared()
    }

    /** Lit les réglages chiffrés puis démarre la session (appelé à l'entrée à l'écran). */
    fun startFromSettings() = viewModelScope.launch { start(store.load()) }
}