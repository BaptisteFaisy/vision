package dev.sam3vision.app.isee

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.app.glasses.GlassesSessionControl
import dev.sam3vision.app.isIntroDisablePhrase
import dev.sam3vision.app.media.CacheCleanerManager
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MediaDatabase
import dev.sam3vision.app.loadedSettings
import dev.sam3vision.app.pose.PitchMeter
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speechClient
import dev.sam3vision.app.voicenav.GuidanceLifecycle
import dev.sam3vision.app.voicenav.VoiceNavSession
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.app.isee.vm.DictationActions
import dev.sam3vision.app.isee.vm.DictationRouterText
import dev.sam3vision.app.isee.vm.IseeAnswers
import dev.sam3vision.app.isee.vm.IseeCameraCoordinator
import dev.sam3vision.app.isee.vm.IseeDictation
import dev.sam3vision.app.isee.vm.IseeDictationRouter
import dev.sam3vision.app.isee.vm.IseeGlassesOrder
import dev.sam3vision.app.isee.vm.IseeHoldMic
import dev.sam3vision.app.isee.vm.IseeLiveDictation
import dev.sam3vision.app.isee.vm.IseeStreamDictation
import dev.sam3vision.app.isee.vm.IseeSpeedOrder
import dev.sam3vision.app.isee.vm.IseePhotoShot
import dev.sam3vision.app.isee.vm.IseePicto
import dev.sam3vision.app.isee.vm.IseePictoVoice
import dev.sam3vision.app.isee.vm.IseeStatusText
import dev.sam3vision.app.isee.vm.IseeSteeringOrders
import dev.sam3vision.app.isee.vm.IseeTranscript
import dev.sam3vision.app.isee.vm.IseeVoice
import dev.sam3vision.app.isee.vm.IseeModeOrders
import dev.sam3vision.app.isee.vm.IseeVoiceNav
import dev.sam3vision.app.isee.vm.PictoPhase
import dev.sam3vision.core.glasses.CaptureAction
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.picto.FindIndoorClips
import dev.sam3vision.core.picto.FindIndoorLevels
import dev.sam3vision.core.picto.FindIndoorTarget
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.net.MetaImageClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SettingsVoiceCommand
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Le chef d'orchestre de l'écran unique (chat, caméra : vidéo du tap, photo
 * automatique de l'appui long) : il tient l'état affiché et branche les
 * collaborateurs — [IseeTranscript] (bulles), [IseeDictation] (micro et
 * transcription), [IseeAnswers] (question → réponse → voix), [IseePhotoShot]
 * (photo-contexte des questions), [IseeCameraCoordinator] (vidéo), plus la
 * lecture, l'intro et la sonde déjà extraites.
 *
 * Lui-même ne fait que conduire : gestes, cycle de vie, et réglages lus une fois.
 */
class IseeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SettingsStore = SettingsStoreFactory.create(app)
    private val dao = MediaDatabase.get(app).messages()
    private val media = MediaCacheStore(app, dao)
    private val cleaner = CacheCleanerManager(dao, media.dir())
    private val haptics = IseeHaptics(app)

    /**
     * Voix d'iSee : la fin de lecture ferme les sous-titres, aussitôt, et
     * rend le bus vocal (erreur comprise : une lecture finie, même en
     * échec, ne tient plus son rang).
     */
    private val player = iseeVoicePlayer(
        app,
        onComplete = { update { st -> st.copy(speakingEntryId = null, speakingText = "") }; VoiceBus.onSpeechIdle() },
        onPlaybackError = { update { st -> st.copy(speakingEntryId = null, speakingText = "") }; VoiceBus.onSpeechIdle() },
    )

    private val _state = MutableStateFlow(IseeUiState())
    val state: StateFlow<IseeUiState> = _state

    /** Fermeture / arrière-plan : purge et minuteur, hors de la vie du ViewModel. */
    private val session = IseeSession(cleaner, player, ::update)

    /** SAM quand la caméra est active : masques, distances, latence et phrase à dire. */
    val probe = LiveProbe(viewModelScope, appContext = getApplication())
    private val lensFeeds = dev.sam3vision.app.live.IseeLensFeeds(getApplication())
    private val pitch = PitchMeter(app, probe.distances) // IMU → pose, null sans capteur

    /** Ordres cap/Traversée + guidage auto, extraits du ViewModel (limite 500). */
    private val steering = IseeSteeringOrders(probe, { settings }, ::speakNavigation, ::update, phoneLens = { camera?.lensSpec }, glassesLens = lensFeeds::mire)

    /** Repères visuels : photo → picto Muse (même clé que l'ASR) → overlay temps réel. */
    val picto = IseePicto(
        media = media,
        camera = { camera },
        settings = { settings },
        store = PictoStore(pictosDir(app.filesDir)),
        clientOf = { s -> MetaImageClient(s.asrBaseUrl, s.asrApiKey) }, placeMode = { _state.value.placeMode }, probeState = probe.state,
        update = ::update,
        scope = viewModelScope,
        onFound = ::speakFound,
    )
    /** Zone tactile publiée par la puce Traversée (exemption d'interception racine). */
    val crossingChip = CrossingChipZone()
    /** Zones tactiles des boutons navigation sur l'accueil (exemption racine). */
    val navButtons = NavButtonZones()
    // Arbitrage voix : obstacle → TwinLite, sinon SAM (ordre user), sonde + guetteurs + traversée.
    private val systemTts = SystemTtsEngine.create(getApplication())
    /** Clips anglais (SPEC v4) : le même pack sert les clips seuls et la voix de proximité. */
    private val voicePack = guideVoicePack(getApplication())
    /** Voix de proximité (SPEC v4) : clips d'abord, synthèse GPS sinon — parlée par le bus. */
    private val proximity = ProximityVoice(
        player, { settings }, ::speakAnnouncement,
        clips = voicePack,
        systemBusy = { systemTts.speaking },
        userSpeaking = { _state.value.holding || _state.value.transcribing },
    )
    /** Lecteur des clips anglais : séquences et clips seuls, joués par le bus. */
    private val clipPlayer = IseeClipPlayer(voicePack, { settings.ttsVoiceId }, SpeechPlayerClipSink(player), ::speakAnnouncement)

    /** Guetteurs bordure + escaliers + mobilier + trottoir, posés par `observeHazards` (reset à chaque début de séance). */
    private var hazards: ProbeHazards? = null

    /** Voix de l'intro (le texte écrit, lui, ne part jamais). */
    private val intro = IseeIntro(getApplication(), player, { settings }, { settings = it }, prefs, ::update)

    /** Bulles du chat, reconstruites depuis Room. */
    private val transcript = IseeTranscript(dao, ::update)

    /** Voix ElevenLabs d'un texte de bulle : octets vides si rien à dire. */
    private val voice = IseeVoice({ settings }, ::update)

    /** Lecture des bulles, extraite pour garder ce fichier sous 500 lignes. */
    private val playback = IseePlayback(
        dao = dao,
        media = media,
        player = player,
        update = ::update,
        synthesize = { text -> voice.synthesize(text) },
    )

    /** Photo-contexte des questions : frame filmée captée à chaque appui long. */
    private val shots = IseePhotoShot(
        media = media,
        camera = { camera },
    )

    /** Micro de l'appui long, transcription incluse. */
    private val mic = IseeHoldMic()
    private val streamDictation = IseeStreamDictation(viewModelScope, mic::setChunkListener, ::update)
    private val dictation = IseeDictation(mic, { s -> streamDictation.finalClient(speechClient(s)) }, ::update, onSettled = streamDictation::stop)
    private val liveDictation = IseeLiveDictation(viewModelScope, mic::preview, ::speechClient, ::update)

    /** Vidéo d'environnement du tap, et lien aux actions de la caméra. */
    private val cameraOps = IseeCameraCoordinator(
        media = media,
        camera = { camera },
        update = ::update,
        scope = viewModelScope,
        onRecordingStarted = { probe.begin(); proximity.reset(); hazards?.reset(); VoiceBus.reset(); AntiRepeat.reset() },
        prune = ::enforceFifo,
    )

    /** Question dictée → réponse du LLM → texte et voix. */
    private val answers = IseeAnswers(
        media = media,
        settings = { settings },
        update = ::update,
        transcript = transcript,
        playback = playback,
        photo = shots,
        prune = ::enforceFifo,
        crossingActive = { probe.state.value.crossingActive },
    )

    /**
     * Navigation vocale in-app ([IseeVoiceNav], paresseuse) : active dès que
     * `routeBaseUrl` est seedée — coût nul sans URL Valhalla.
     */
    private val voiceNav = IseeVoiceNav(getApplication(), { settings }, viewModelScope, ::speakNavigation,
        speakRoute = { text, level -> speakGps(text, level) },
        // Fin de guidage : le lecteur est coupé (une coupe ne termine
        // jamais : pas de `onComplete`) — l'état parlant est rendu ici.
        stopRoute = { player.stop(); update { it.copy(speakingEntryId = null, speakingText = "") } },
        onRouteCap = { cap -> if (cap == null) probe.clearRouteCap() else probe.setRouteCap(cap) },
        userSpeaking = { _state.value.holding || _state.value.transcribing },
        setGuidance = { enabled ->
            if (settings.navigationGuidanceEnabled != enabled) settings = settings.copy(navigationGuidanceEnabled = enabled)
            viewModelScope.launch { runCatching { prefs.save(settings) } }
        },
    )

    /** État de la navigation vocale (repos, confirmation, guidage) pour l'écran. */
    val voiceNavState: StateFlow<VoiceNavUiState> get() = voiceNav.state

    /** La session sous-jacente (boutons de l'écran navigation). */
    fun voiceNav(): VoiceNavSession = voiceNav.session

    /** Ordre dicté sur le direct : routeur « mode … » d'abord, repli historique ensuite. */
    suspend fun acceptNavOrder(transcript: String): Boolean {
        if (dictationRouter.run(transcript)) return true
        if (voiceNav.run(transcript)) return true
        return steering.runCrossing(transcript)
    }

    /** Arrêter le guidage (bouton du bandeau d'accueil) ou quitter l'écran navigation. */
    fun dismissVoiceNav() = voiceNav.dismiss()

    /** Ordres de mode (lieu, picto), rejoués par le routeur sous leurs phrases canoniques. */
    private val modes = IseeModeOrders({ settings }, ::speakNavigation, ::update, picto::labels, ::runFindOrder, picto::findObject, { _state.value.picto.hits }, { _state.value.placeMode }, post = { level, text, clip -> VoiceBus.post(level, text, clip) })

    /**
     * Routeur de dictée (spec v4) : les 10 ordres « mode … » rejouent les
     * collaborateurs existants sous leurs phrases canoniques ; tout le reste
     * part au LLM comme question. Sans condition de mode : actif partout,
     * traversée comprise. « mode glasses on/off » rejoue la bascule « iSee »
     * (pas d'API on/off explicite côté lunettes, voir rapport).
     */
    private val dictationRouter = IseeDictationRouter(object : DictationActions {
        override suspend fun onOutdoor() { modes.run("mode dehors") }
        override suspend fun onIndoor() { modes.run("mode dedans") }
        override suspend fun onCrossing(active: Boolean) {
            steering.runCrossing(if (active) "crossing mode on" else "crossing mode off")
            onCrossingChanged()
        }
        override suspend fun onFind(label: String) { modes.run("find the " + label) }
        override suspend fun onFindOff() {
            val confirm = DictationRouterText.findStopped(settings.speechLanguage)
            update { it.copy(picto = it.picto.copy(phase = PictoPhase.Idle, hits = emptyList()), status = confirm) }
            speakNavigation(confirm)
        }
        override suspend fun onGpsGo(destination: String) { voiceNav.run("I want to go to " + destination) }
        override suspend fun onGpsOff() { voiceNav.runOrder("disable the GPS") }
        override suspend fun onGlasses(on: Boolean) { glassesOrder.run("iSee") }
    })
    private val glassesInbox = IseeGlassesInbox(viewModelScope, shots, { _state.value }, ::update, ::silenceForGlasses, ::answerHeard, cameraOps::stopVideo, probe::stop, { cameraOps.startVideo(); syncProbe() })
    private val holdTalk = IseeHoldTalk(viewModelScope, { active }, ::silenceForGlasses, shots, { _state.value }, ::update, haptics, dictation, liveDictation, streamDictation, { settings }, cameraOps, ::transcribeAndAnswer, glassesInbox::onTap)
    private val glassesOrder = IseeGlassesOrder(::speakNavigation, ::update, { glassesSession })

    /** Ordre vocal de vitesse : réglage persisté, confirmation à la nouvelle vitesse. */
    private val speedOrder = IseeSpeedOrder({ settings }, { updated -> settings = updated; viewModelScope.launch { runCatching { prefs.save(settings) } } }, { player.speed = it }, ::dropHeard, viewModelScope, voice::synthesize, { text, bytes -> playback.speakBytes(SPEED_BUBBLE_ID, text, bytes) })

    /** Itinéraire guidé + dernier fix GPS pour les cartes (`null` au repos). */
    fun debugNavSnapshot(): Pair<NavRoute?, NavFix?> = runCatching { voiceNav.debugRoute() to voiceNav.debugFix() }.getOrDefault(null to null)
    /** Recette adb : guidage debug vers [destination] (debug seul, voir [IseeVoiceNav]). */
    fun debugNavTo(destination: String) = voiceNav.debugNavTo(destination)

    /** Vrai quand l'écran iSee est à l'écran : les gestes ne touchent que lui. */
    @Volatile
    var active: Boolean = false
        set(value) {
            field = value
            if (!value) onHoldCancel()
        }

    /** Caméra de la fenêtre flottante, câblée par le composable à l'attachement. */
    var camera: IseeCameraActions? = null

    /** Bascule de session pour l'ordre vocal « iSee », câblée par AppNav (`GlassesBridge`). */
    var glassesSession: GlassesSessionControl? = null

    private var settings: Settings = Settings.Default


    init {
        // Le texte d'intro est le premier message une fois pour toutes.
        update { it.copy(introVisible = true) }
        viewModelScope.launch {
            // Réouverture complète : rien d'une session précédente ne survit. La purge
            // est aussi faite ici, car un force-stop ou un balayage des apps récentes
            // ne passe pas par onDestroy — le téléphone gardait alors les messages.
            runCatching { cleaner.purgeAll() }
            settings = loadedSettings(prefs)
            // Porte GPS non persistée : elle rouvre à chaque redémarrage — un
            // ordre « désactive le GPS » d'hier ne tait pas la voix d'aujourd'hui.
            syncProbe()
            transcript.refresh()
            picto.refresh()
            intro.playIfPending()
        }
        // Bus vocal (SPEC v4) : branché une fois ici — porte-parole (clips
        // anglais, synthèse GPS), reprise chat au repos, parole du user.
        VoiceBus.speaker = BusSpeaker(viewModelScope, player, clipPlayer, proximity)
        VoiceBus.onIdle = { viewModelScope.launch { resumeChatIfDue() } }
        viewModelScope.launch {
            state.collect { VoiceBus.userSpeaking(it.holding || it.transcribing) }
        }
        probe.observeSpoken(viewModelScope, placeMode = { _state.value.placeMode })
        // Placement sur le passage (mot + approche + arrivée, niveau 8).
        observeCrossingVoice(viewModelScope, placeMode = { _state.value.placeMode })
        probe.observeCrossing(viewModelScope, haptics)
        probe.observeGlassesLive(viewModelScope, { settings }, { camera?.lensSpec }, { pitch.stop() }, { pitch.start() }, lensFeeds::mire)
        probe.observeHeadPose(viewModelScope)
        probe.observeDetour(viewModelScope, voiceNav::onProbeDetour)
        hazards = probe.observeHazards(viewModelScope, { settings.speechLanguage }, placeMode = { _state.value.placeMode })
        probe.observeIndoorDoors(
            viewModelScope,
            { level, text, clip -> VoiceBus.post(level, text, clip) },
            AntiRepeat::allow,
            { _state.value.placeMode },
        )
    }

    /** Bascule du mode Traversée (puce de la carte caméra) : mêmes masques, voix traversée ou retour. */
    fun toggleCrossing() {
        steering.toggleCrossing()
        onCrossingChanged()
    }

    /**
     * Traversée basculée (SPEC v4) : à l'allumage, le chat en cours est
     * coupé (il attendra sa reprise) et la voix GPS se tait — le verdict
     * prend le son ; à l'arrêt, la réponse coupée reprend s'il y a lieu.
     */
    private fun onCrossingChanged() {
        if (probe.state.value.crossingActive) {
            cutSpeech()
            voiceNav.stopVoice()
        } else {
            viewModelScope.launch { resumeChatIfDue() }
        }
    }

    override fun onCleared() {
        liveDictation.stop()
        streamDictation.stop()
        dictation.discard()
        player.release()
        voiceNav.shutdown()
        probe.stop()
        pitch.stop()
        super.onCleared()
    }

    fun attachCamera(actions: IseeCameraActions) { camera = actions }

    fun detachCamera() { camera = null }

    /**
     * Caméra liée ou coupée (remonté par la liaison) : coupée, la sonde est arrêtée
     * net (sessions fermées, la carte peut retomber à zéro) ; liée, les sessions
     * rouvrent et la première frame réveille la carte.
     */
    fun setCameraActive(active: Boolean) {
        if (_state.value.cameraActive == active) return
        update { it.copy(cameraActive = active) }
        if (active) {
            pitch.startUnlessGlassesLive()
            syncProbe()
        } else {
            pitch.stop()
            probe.stop()
        }
    }

    fun onGlassesPhoto(file: File, action: CaptureAction) = glassesInbox.onPhoto(file, action)
    fun dismissGlassesPhoto() = glassesInbox.dismiss()
    fun onGlassesListening() = silenceForGlasses()
    fun onGlassesHeard(text: String, pcm: ByteArray) = glassesInbox.heard(text, pcm)

    /** iSee se tait (réponse en vol + lecture + guidage) : la voix du user a priorité. */
    private fun silenceForGlasses() {
        answers.cancelInFlight()
        cutSpeech()
        voiceNav.stopVoice()
    }

    /** Coupe la lecture en cours et rend l'état parlant : la parole du user passe devant. */
    private fun cutSpeech() {
        if (!playerIdle()) {
            player.stop()
            update { it.cutSpeaking() }
        }
    }

    /**
     * Parole GPS vers le bus (SPEC v4) : manœuvres au rang 11, rappel au
     * rang 3 — tus en Traversée (le verdict y est propriétaire du son) et
     * dedans (chaîne dedans : find, user, humain, chat, porte et murs).
     */
    private fun speakGps(text: String, level: Int) {
        if (probe.state.value.crossingActive) return
        if (_state.value.placeMode != PictoPlaceMode.DEHORS) return
        VoiceBus.post(level, text)
    }

    /** Reprise au repos : la réponse coupée reparle (rang 9, hors Traversée). */
    private suspend fun resumeChatIfDue() {
        if (probe.state.value.crossingActive) return
        runCatching { playback.speakPending() }
    }

    /**
     * Trouvé de la chasse (SPEC v4, cran find 0) : « Keys at 9 o'clock. »
     * en clip anglais, clé par clip (changement aussitôt, même trouvé 2 s).
     */
    private fun speakFound(label: String, clockHour: Int) {
        val target = FindIndoorTarget.fromSpoken(label) ?: return
        val clip = FindIndoorClips.found(target, clockHour)
        if (AntiRepeat.allow(AntiRepeat.KEY_FIND_FOUND + ":" + clip.id, System.currentTimeMillis())) {
            VoiceBus.post(FindIndoorLevels.FIND, clip.text, clip.id)
        }
    }

    /** Texte entendu (lunettes ou dictée) : ordres locaux d'abord, sinon question au LLM. */
    private suspend fun answerHeard(text: String, pcm: ByteArray) {
        if (isIntroDisablePhrase(text)) {
            dropHeard()
            intro.disable()
            return
        }
        if (speedOrder.run(text)) return
        if (runSettingsCommand(text)) return
        if (glassesOrder.run(text)) return
        handleDictated(text, pcm)
    }

    /** Réglages enregistrés depuis l'écran Settings : rechargement brûlant. */
    fun refreshSettings() = viewModelScope.launch {
        settings = loadedSettings(prefs)
        syncProbe()
        transcript.refresh()
    }

    /** Les Réglages pilotent la sonde SAM (mode Traversée préservé) et la vitesse de lecture. */
    private fun syncProbe() {
        probe.setCrossingActive(settings, probe.state.value.crossingActive, sensorLens = camera?.lensSpec, onLens = lensFeeds::note, glassesLens = lensFeeds.mire())
        probe.cadence.continuous = settings.continuousCadenceEnabled
        player.speed = settings.ttsSpeed
    }

    // ————— Gestes —————

    fun onHoldStart() = holdTalk.start()
    fun onHoldEnd() = holdTalk.end()
    fun onHoldCancel() = holdTalk.cancel()
    fun onSingleTap() = holdTalk.singleTap()

    // ————— Appui long : micro → texte → réponse parlée —————

    /** Fin de la tenue : transcription, coupe d'intro éventuelle, puis réponse IA. */
    private suspend fun transcribeAndAnswer() {
        // Voix partie pendant la tenue (annonce, réponse tardive) : coupée, et la
        // nouvelle question prend sa place (overwrite) — jamais d'erreur « busy ».
        cutSpeech()
        // Échec de dictée : le motif est déjà affiché, la conversation reste à l'écran.
        // Le contexte photo de cette tenue est relâché : il ne gèle pas à vie.
        val heard = dictation.finish(settings) ?: run {
            shots.releaseContext()
            return
        }
        answerHeard(heard.text, heard.audio)
    }

    /**
     * Ordre vocal d'ouverture des Réglages (« settings », « réglages ») : la
     * navigation pousse la page, la dictée ne part ni au LLM ni ailleurs.
     * Vrai si [text] portait l'ordre.
     */
    private fun runSettingsCommand(text: String): Boolean {
        if (!SettingsVoiceCommand.isSettingsOrder(text)) return false
        dropHeard()
        update { it.copy(openSettings = true) }
        return true
    }

    /** La navigation a consommé la demande : ne pas rouvrir les réglages. */
    fun settingsOpened() = update { it.copy(openSettings = false) }

    /** Ordres « Find the X » / « Where are my keys » / « où sont mes clés » : X rejoint les masques SAM, son picto se crée à sa première détection puis s'ancre sur lui. */
    private fun runFindOrder(label: String) {
        probe.ensureConcept(settings, label, camera?.lensSpec, lensFeeds::note, lensFeeds.mire())
        picto.findObject(label)
    }

    /** Texte transcrit : coupe d'intro possible, ligne Room + MP3, puis question au LLM. */
    private suspend fun handleDictated(text: String, pcm: ByteArray) {
        if (text.isBlank()) {
            dropHeard(IseeStatusText.NOTHING_HEARD)
            return
        }
        // Pas de garde « thinking » : une nouvelle parole écrase la réponse en vol
        // (ordres locaux directs, question préemptée dans `IseeAnswers.answer`).
        // Routeur (spec v4) : « mode … » ⇒ ordre exécuté ; tout le reste ⇒ question LLM.
        // Les anciens ordres sans « mode » (maison, cap, GPS, traversée, clarification)
        // sont supprimés : leurs phrases partent au LLM comme questions.
        if (dictationRouter.run(text)) return
        val row = answers.recordQuestion(pcm, text)
        enforceFifo()
        transcript.refresh()
        answers.answer(row, text)
    }

    /** Sortie précoce après une tenue : micro rendu, contexte photo relâché, motif éventuel. */
    private fun dropHeard(status: String? = null) {
        dictation.discard()
        shots.releaseContext()
        if (status != null) update { it.copy(status = status) }
    }

    // ————— Coulisses —————

    private fun update(fn: (IseeUiState) -> IseeUiState) = _state.update(fn)

    private fun playerIdle(): Boolean = _state.value.speakingEntryId == null



    /** Position média pour les sous-titres ; aucune estimation d'une vitesse de parole. */
    fun speechPosition() = player.position()

    private fun speakAnnouncement(text: String) {
        update { it.copy(speakingEntryId = if (text.isBlank()) null else ANNOUNCEMENT_BUBBLE_ID, speakingText = text) }
    }

    /** Annonce de navigation : même chemin audio que les bulles (ElevenLabs + lecteur partagé). */
    private suspend fun speakNavigation(text: String): Unit {
        val audio = voice.synthesize(text)
        if (audio.isEmpty()) return
        speakAnnouncement(text)
        player.play(audio).onFailure { update { it.copy(speakingEntryId = null, speakingText = "") } }
    }

    /** Sécurité FIFO silencieuse après chaque écriture de fichier multimédia. */
    private suspend fun enforceFifo() {
        runCatching { cleaner.enforceFifoAfterWrite() }
    }

    /** Purge totale à la fermeture de l'activité (option A) : table + cache. */
    fun onAppClosed() {
        onHoldCancel()
        session.onClosed()
    }

    /** L'app part derrière : minuteur armé (option C), sonde arrêtée sauf session lunettes. */
    fun onBackground() {
        onHoldCancel()
        session.onBackground()
        IseeBackground.enter(probe)
        // Guidage affiché sur l'accueil : GPS + voix maintenus par le service (W1).
        if (voiceNav.state.value is VoiceNavUiState.Guiding) GuidanceLifecycle.startBackground(getApplication())
    }

    /** L'app revient au premier plan : minuteur annulé (option C), sessions rouvertes. */
    fun onForeground() {
        GuidanceLifecycle.stopBackground(getApplication())
        session.onForeground()
        probe.onForeground()
        syncProbe()
    }

}
