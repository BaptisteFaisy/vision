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
import dev.sam3vision.app.live.LivePredictionWiring
import dev.sam3vision.app.live.sendFrame
import dev.sam3vision.app.live.LivePositionVoice
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
import dev.sam3vision.core.mask.colorizedMaskBitmap
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.net.HttpTrackClient
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WsLiveClient
import dev.sam3vision.core.net.liveClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestration du direct : une frame à la fois (gate + cadence adaptative), masques
 * recolorés, statistiques — et **mémoire** des objets vus ([LiveTimeline]), qui permet de
 * poser des questions sur ce qui est passé il y a quelques secondes.
 *
 * Le ViewModel ne garde que l'orchestration : le réveil du GPU ([LiveWakeGuard]), les ips
 * ([LiveFpsMeter]), la cadence ([LiveFrameCadence]), le décodage des masques
 * ([LiveMaskDecoder]), la narration ([LiveNarration]), les annonces de position
 * ([LivePositionVoice]) et les questions ([LiveAskSession]) vivent dans `live/`, sans
 * Android pour l'essentiel — donc testables en JVM. L'API publique ne change pas.
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

    /** Transport choisi dans le bandeau : tracker à ids stables, ou `/live` en WebSocket. */
    private var trackerEnabled = false

    /**
     * Pipeline actif, quel que soit le transport : la cadence, l'époque de la séance et la
     * latence mesurée passent par lui — l'écran ne connaît pas le client utilisé.
     */
    internal val pipeline: FramePipeline? get() = tracker ?: session

    /** Dernière frame gardée pour les questions (indépendant de SAM). */
    private var lastKeepAt = 0L

    /** Chronologie de la frame courante (ns monotones, même base que `imageInfo.timestamp`). */
    internal val chronology = ScreenTimings { SystemClock.elapsedRealtimeNanos() }

    /** Journal du direct : 60 s d'objets suivis, identités stables. */
    private val timeline = LiveTimeline()

    /** Distances caméra→objets : sténopé + hauteurs connues, lissées par piste. */
    private val distances = DistanceMeter()

    /** Prédiction locale des boîtes (T8) : le câblage, tout le reste vit dans `live/`. */
    private val prediction = LivePredictionWiring(_state)
    private var sensorLens: LensSpec? = null

    private var settings: Settings = Settings.Default

    /** Fenêtre d'attente du premier réveil : au-delà, une frame en échec est une erreur. */
    private val wake = LiveWakeGuard()

    /** Ips sur l'intervalle réel entre deux frames publiées, trous rejetés. */
    private val fps = LiveFpsMeter()

    /** Une frame en vol maximum, cadence adaptative de la session. */
    private val cadence = LiveFrameCadence()

    /** Masques coloriés hors thread principal ; le poste `overlays` est posé au retour. */
    private val masks: LiveMaskDecoder<Bitmap> = LiveMaskDecoder(
        paint = MaskPainter { mask, encoding, argb, alpha -> colorizedMaskBitmap(mask, encoding, argb, alpha) },
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
        if (!this.settings.llmConfigured) {
            _state.update { it.copy(error = LLM_ABSENT_LINE) }
            return
        }
        resetForStart(prompts)
        narration.open(inferHub(this.settings))
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
        // Deux transports pour les frames, et **aucun `/health`** dans les deux cas : la
        // première frame réveille le conteneur (Modal met la requête en file pendant le
        // démarrage, mesuré 8-35 s). Le tracker ouvre une session et suit des ids stables ;
        // `/live` en WebSocket est le défaut.
        session = null
        client = null
        tracker = null
        if (trackerEnabled) {
            tracker = TrackRunner(HttpTrackClient(base))
        } else {
            val live = liveClient(base, marks = chronology)
            client = live
            session = LiveSession(live, prompts = _state.value.prompts, maxObjects = this.settings.objectsOnScreen)
        }
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
        val chosen = LivePrompts.normalize(prompts)
        // Journal et historique de questions : vidés par `asker.reset()` (une seule
        // implémentation de « la mémoire du direct s'efface », partagée avec `stop()`).
        asker.reset()
        distances.enabled = settings.distanceOverlayEnabled
        distances.reset()
        prediction.reset(enabled = settings.boxPredictionEnabled)
        distances.setLens(chosenLens())
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
        val list = promptsText.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        restart(if (list.isEmpty()) LivePrompts.Default else list)
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

    /**
     * Bascule le transport du direct depuis le bandeau : `/live` en WebSocket ↔ tracker
     * par sessions (ids stables). Le choix vit le temps de l'app (non persisté) ; la
     * séance est relancée dans le nouveau mode.
     */
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
    fun onFrame(jpegB64: String, captureAtNs: Long = 0L) {
        if (jpegB64.isBlank()) return
        narration.narrate(jpegB64)
        prediction.publish(System.currentTimeMillis(), lastKnownWidth, lastKnownHeight)
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
            val result = sendFrame(active, jpegB64)
            if (!active.isCurrent(era)) return@launch
            chronology.markReceived()
            if (result == null) {
                publishStages(null)
                return@launch
            }
            result.fold(
                onSuccess = { frame -> publish(frame) },
                onFailure = { error ->
                    publishStages(null)
                    val waking = _state.value.starting && wake.isWaking()
                    _state.update {
                        it.copy(
                            objectsCount = 0,
                            transport = transportLabel(),
                            starting = waking,
                            error = if (waking) null else samUnreachableLine(error.message ?: "GPU injoignable"),
                        )
                    }
                },
            )
        }
    }

    /**
     * Envoie la frame au pipeline actif, quel que soit le transport ; `null` = frame jetée
     * (créneau pris — la caméra n'attend jamais le réseau).
     */
    private suspend fun sendFrame(active: FramePipeline, jpegB64: String): Result<LiveFrame>? =
        when (active) {
            is TrackRunner -> active.sendFrame(_state.value.prompts, jpegB64)
            is LiveSession -> active.runFrame(jpegB64)
            else -> null
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
        // Ancre des boîtes extrapolées entre deux inférences (inerte si le flag est éteint).
        prediction.absorb(frame, now, width, height)
        val readings = distances.measure(width, height, detections, seen.map { it?.id })
        positions.announce(frame, width, height, now, readings)
        // Calcul hors thread principal (voir LiveMaskDecoder) ; le poste `overlays` est posé là-bas.
        val overlays = masks.decode(frame)
        val gap = fps.gapAt(now)
        val recent = timeline.recent(now, MEMORY_WINDOW_MS)
        publishStages(frame)
        _state.update { current ->
            current.copy(
                frame = frame,
                overlays = overlays,
                objectsCount = frame.objects.size,
                latencyMs = pipeline?.stats?.averageMs ?: 0L,
                fps = LiveFpsMeter.fpsFor(gap, current.fps),
                error = null,
                starting = false,
                memory = current.memory.copy(tracks = recent.tracks),
                distanceLayer = if (readings.isEmpty()) null else DistanceLayer(width, height, readings),
            )
        }
        // Boîtes de la frame qui vient d'arriver : le masque serveur reprend la main,
        // la prédiction ne repart qu'après.
        prediction.publish(now, width, height)
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
        distances.setLens(chosenLens())
    }

    /** Calibrage retenu : réglage utilisateur, puis capteur, puis [LensSpec.Default]. */
    private fun chosenLens(): LensSpec {
        val forced = settings.cameraFovDeg
        if (forced > 0f) return LensSpec.fromHorizontalFov(forced)
        return sensorLens ?: LensSpec.Default
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

    /**
     * Bouton « Stop » : le direct s'arrête **pour de vrai**.
     *
     * - la frame en vol est révoquée ([LiveSession.reset]) : sa réponse tardive est jetée,
     *   elle ne réécrit pas l'état « arrêté » et ne vole pas le créneau de la séance
     *   suivante ;
     * - la mémoire du direct (journal des objets et historique des questions) disparaît
     *   maintenant, pas au démarrage suivant : un direct arrêté ne montre plus — et ne
     *   répond plus sur — ce qu'il a vu.
     */
    fun stop() {
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
        viewModelScope.launch { closing?.stop() }
        distances.reset()
        prediction.reset(enabled = false)
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

    /** « Tracker » en mode sessions, sinon « WebSocket » ou « WebSocket KO » (aucun repli POST). */
    private fun transportLabel(): String = when {
        trackerEnabled -> TRANSPORT_TRACK
        (client as? WsLiveClient)?.isConnected == true -> TRANSPORT_WS
        else -> TRANSPORT_WS_KO
    }

    /** Lit les réglages chiffrés puis démarre la session (appelé à l'entrée à l'écran). */
    fun startFromSettings() = viewModelScope.launch { start(store.load()) }

    private companion object {
        /** Fenêtre du journal envoyée au LLM : 30 s suffisent pour « il y a 5 s ». */
        const val MEMORY_WINDOW_MS = 30_000L

        /** Dossier audio de l'annonce des positions : distinct de celui des bandeaux. */
        const val POSITION_VOICE_DIR = "positions"
    }
}