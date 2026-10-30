package dev.sam3vision.app.isee

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.app.isIntroDisablePhrase
import dev.sam3vision.app.media.CacheCleanerManager
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MediaDatabase
import dev.sam3vision.app.navigation.AndroidNavigationLauncher
import dev.sam3vision.app.loadedSettings
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speechClient
import dev.sam3vision.app.voicenav.GuidanceLifecycle
import dev.sam3vision.app.voicenav.VoiceNavSession
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.app.isee.vm.IseeAnswers
import dev.sam3vision.app.isee.vm.IseeCameraCoordinator
import dev.sam3vision.app.isee.vm.IseeDictation
import dev.sam3vision.app.isee.vm.IseeHoldMic
import dev.sam3vision.app.isee.vm.IseeLiveDictation
import dev.sam3vision.app.isee.vm.IseeNavClarify
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
import dev.sam3vision.app.isee.vm.MaisonEnterOrder
import dev.sam3vision.app.isee.vm.NavigationHook
import dev.sam3vision.core.glasses.CaptureAction
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.net.MetaImageClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SettingsVoiceCommand
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
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

    /** Voix d'iSee : la fin de lecture ferme les sous-titres, aussitôt. */
    private val player = iseeVoicePlayer(
        app,
        onComplete = { update { st -> st.copy(speakingEntryId = null, speakingText = "") } },
        onPlaybackError = { update { st -> st.copy(speakingEntryId = null, speakingText = "") } },
    )

    private val _state = MutableStateFlow(IseeUiState())
    val state: StateFlow<IseeUiState> = _state

    /** Fermeture / arrière-plan : purge et minuteur, hors de la vie du ViewModel. */
    private val session = IseeSession(cleaner, player, ::update)

    /** SAM quand la caméra est active : masques, distances, latence et phrase à dire. */
    val probe = LiveProbe(viewModelScope, appContext = getApplication())

    /** Ordres cap/Traversée + guidage auto, extraits du ViewModel (limite 500). */
    private val steering = IseeSteeringOrders(probe, { settings }, ::speakNavigation, ::update)

    /** Repères visuels : photo → picto Muse (même clé que l'ASR) → overlay temps réel. */
    val picto = IseePicto(
        media = media,
        camera = { camera },
        settings = { settings },
        store = PictoStore(pictosDir(app.filesDir)),
        clientOf = { s -> MetaImageClient(s.asrBaseUrl, s.asrApiKey) },
        update = ::update,
        scope = viewModelScope,
    )
    /** Zone tactile publiée par la puce Traversée (exemption d'interception racine). */
    val crossingChip = CrossingChipZone()
    /** Zones tactiles des boutons navigation sur l'accueil (exemption racine). */
    val navButtons = NavButtonZones()
    // Arbitrage voix : obstacle → TwinLite, sinon SAM (ordre user), sonde + guetteurs + traversée.
    /** Voix de proximité : dit les objets et leur distance une fois par scène. */
    private val proximity = ProximityVoice(player, { settings }, ::speakAnnouncement, ::canAnnounce, clips = guideVoicePack(getApplication()), routeSpeaking = ::isRouteSpeaking)

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
    private val dictation = IseeDictation(mic, ::speechClient, ::update)
    private val liveDictation = IseeLiveDictation(viewModelScope, mic::preview, ::speechClient, ::update)

    /** Vidéo d'environnement du tap, et lien aux actions de la caméra. */
    private val cameraOps = IseeCameraCoordinator(
        media = media,
        camera = { camera },
        update = ::update,
        scope = viewModelScope,
        onRecordingStarted = { probe.begin(); proximity.reset(); hazards?.reset(); voiceNavVoiceGate.openWindow() },
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
    )

    /** Ordre vocal « je veux aller à X » : Google Maps + annonce, avant le LLM. */
    private val navigation = NavigationHook(
        launcher = AndroidNavigationLauncher(getApplication()),
        guidanceEnabled = { settings.navigationGuidanceEnabled },
        announcer = NavigationAnnouncer({ settings }) { _, text -> speakNavigation(text) },
        update = ::update,
    )

    /**
     * Navigation vocale in-app ([IseeVoiceNav], paresseuse) : active dès que
     * `routeBaseUrl` est seedée — coût nul sans URL Valhalla.
     */
    private val voiceNav = IseeVoiceNav(getApplication(), { settings }, viewModelScope, ::speakNavigation,
        onAutoCrossing = { steering.setAutoCrossing(it) },
        speakRoute = { text, urgent -> proximity.onPhrase(text, canAnnounce(), urgent) },
        stopRoute = player::stop,
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

    /** Destination dictée sur le direct : passe par la session de navigation. */
    suspend fun acceptNavOrder(transcript: String) = voiceNav.run(transcript)

    /** Arrêter le guidage (bouton du bandeau d'accueil) ou quitter l'écran navigation. */
    fun dismissVoiceNav() = voiceNav.dismiss()
    /** Bouton « Démarrer » de la confirmation sur l'accueil. */
    fun confirmVoiceNavDestination() = viewModelScope.launch { voiceNav.session.confirmDestination() }
    /** Bouton « Annuler » de la confirmation sur l'accueil. */
    fun cancelVoiceNavConfirmation() = voiceNav.session.cancelConfirmation()
    /** Saisie clavier sur l'accueil : pseudo-dictée « aller à … » vers la session. */
    fun submitTypedVoiceNavDestination(text: String) = voiceNav.submitTyped(text)

    /** Ordres de mode (maison, carte, picto) : avant la navigation et le LLM. */
    private val modes = IseeModeOrders({ settings }, ::speakNavigation, ::update, picto::labels)

    /** Ordre navigation sans destination comprise : demande, jamais le LLM. */
    private val navClarify = IseeNavClarify({ settings }, ::speakNavigation, ::update)

    /** Ordre vocal de vitesse : réglage persisté, confirmation à la nouvelle vitesse. */
    private val speedOrder = IseeSpeedOrder({ settings }, { updated -> settings = updated; viewModelScope.launch { runCatching { prefs.save(settings) } } }, { player.speed = it }, ::dropHeard, viewModelScope, voice::synthesize, { text, bytes -> playback.speakBytes(SPEED_BUBBLE_ID, text, bytes) })

    /** Itinéraire guidé + dernier fix GPS pour les cartes (`null` au repos). */
    fun debugNavSnapshot(): Pair<NavRoute?, NavFix?> = runCatching { voiceNav.debugRoute() to voiceNav.debugFix() }.getOrDefault(null to null)
    /** Aperçu d'itinéraire de la confirmation pour la carte (`null` hors confirmation). */
    fun confirmNavSnapshot(): Pair<NavRoute?, NavFix?> = voiceNav.confirmPreview to null
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

    private var settings: Settings = Settings.Default

    /** Compteur des photos lunettes affichées : deux photos n'ont jamais le même `seq`. */
    private var glassesPhotoSeq = 0L

    init {
        // Le texte d'intro est le premier message une fois pour toutes.
        update { it.copy(introVisible = true) }
        viewModelScope.launch {
            // Réouverture complète : rien d'une session précédente ne survit. La purge
            // est aussi faite ici, car un force-stop ou un balayage des apps récentes
            // ne passe pas par onDestroy — le téléphone gardait alors les messages.
            runCatching { cleaner.purgeAll() }
            settings = loadedSettings(prefs)
            // GPS laissé désactivé (ordre vocal, réglages) : la voix ambiante
            // reste muette après redémarrage, jusqu'à une action du user.
            voiceNav.syncGate()
            syncProbe()
            transcript.refresh()
            picto.refresh()
            intro.playIfPending()
        }
        probe.observeSpoken(viewModelScope, proximity, { canAnnounce() }, { settings.speechLanguage })
        // Passage piéton reconnu : la voix le dit, une fois, sinon la proposition
        // n'existe que pour ceux qui voient la pastille.
        observeCrossingOffer(probe.state.map { it.crossingOffer }, viewModelScope, { settings.speechLanguage }) { speakNavigation(it) }
        probe.observeCrossing(viewModelScope, haptics)
        probe.observeCrossingActive(viewModelScope, voiceNav::onTraverseModeChanged)
        probe.observeDetour(viewModelScope, voiceNav::onProbeDetour)
        hazards = probe.observeHazards(viewModelScope, proximity, { canAnnounce() }, { settings.speechLanguage })
    }

    /** Bascule du mode Traversée (puce de la carte caméra) : feu+voitures ou retour. */
    fun toggleCrossing() = steering.toggleCrossing()

    override fun onCleared() {
        liveDictation.stop()
        dictation.discard()
        player.release()
        voiceNav.shutdown()
        probe.stop()
        super.onCleared()
    }

    fun attachCamera(actions: IseeCameraActions) {
        camera = actions
    }

    fun detachCamera() {
        camera = null
    }

    /**
     * Caméra liée ou coupée (remonté par la liaison) : coupée, la sonde est arrêtée
     * net (sessions fermées, la carte peut retomber à zéro) ; liée, les sessions
     * rouvrent et la première frame réveille la carte.
     */
    fun setCameraActive(active: Boolean) {
        if (_state.value.cameraActive == active) return
        update { it.copy(cameraActive = active) }
        if (active) {
            voiceNavVoiceGate.openWindow()
            syncProbe()
        } else probe.stop()
    }

    /**
     * Photo prise avec les lunettes : elle s'affiche sur cet écran (personne
     * accompagnée), sans quitter la page courante — posée même depuis un autre
     * écran, elle attend le retour sur iSee. Doublon (même fichier) ignoré,
     * photo suivante remplace la précédente ([acceptGlassesPhoto]). ASK :
     * la photo devient aussi le contexte de la prochaine question dictée.
     */
    fun onGlassesPhoto(file: File, action: CaptureAction) {
        if (!acceptGlassesPhoto(_state.value.glassesPhoto, file)) return
        glassesPhotoSeq += 1
        update { it.copy(glassesPhoto = IseeGlassesPhoto(file, action, glassesPhotoSeq), status = glassesPhotoLabel(action)) }
        if (action == CaptureAction.ASK) shots.setExternalContext(file)
    }

    /** La photo des lunettes est fermée (retour, bouton) : l'écran iSee reste. */
    fun dismissGlassesPhoto() {
        val dismissed = _state.value.glassesPhoto
        update { it.copy(glassesPhoto = null) }
        // Question annulée avec sa photo : le contexte ne doit pas fuir vers
        // la question suivante (déjà consommé, le relâchement est neutre).
        if (dismissed?.action == CaptureAction.ASK) shots.releaseContext()
    }

    /**
     * Écoute mains-libres ouverte aux lunettes : iSee se tait (lecture et
     * guidage coupés) pour ne pas couvrir la question. Honorée même hors écran
     * iSee : le téléphone est en poche, l'utilisateur ne peut pas vérifier.
     */
    fun onGlassesListening() {
        if (!playerIdle()) {
            player.stop()
            update { it.copy(speakingEntryId = null) }
        }
        voiceNav.stopVoice()
    }

    /**
     * Question entendue aux lunettes : photo-contexte affichée sur cet écran
     * (comme [onGlassesPhoto] en ASK), puis même traitement qu'une dictée
     * tenue-relâchée ([handleDictated]). Le téléphone reste en poche.
     */
    fun onGlassesHeard(photo: File?, text: String, pcm: ByteArray) {
        onGlassesListening()
        if (photo != null) onGlassesPhoto(photo, CaptureAction.ASK)
        viewModelScope.launch {
            if (isIntroDisablePhrase(text)) {
                dropHeard()
                intro.disable()
                return@launch
            }
            if (speedOrder.run(text)) return@launch
            if (runSettingsCommand(text)) return@launch
            handleDictated(text, pcm)
        }
    }

    /** Réglages enregistrés depuis l'écran Settings : rechargement brûlant. */
    fun refreshSettings() = viewModelScope.launch {
        settings = loadedSettings(prefs)
        voiceNav.resumeGps()
        syncProbe()
        transcript.refresh()
    }

    /** Les Réglages pilotent la sonde SAM (mode Traversée préservé) et la vitesse de lecture. */
    private fun syncProbe() {
        probe.setCrossingActive(settings, probe.state.value.crossingActive)
        player.speed = settings.ttsSpeed
    }

    // ————— Gestes —————

    /** Appui long : la frame courante devient le contexte (sauf photo gelée), puis le micro s'ouvre. */
    fun onHoldStart() {
        if (!active) return
        // Parler coupe la lecture en cours (sinon la tenue est jetée, le micro reste pris).
        if (!playerIdle()) {
            player.stop()
            update { it.copy(speakingEntryId = null) }
        }
        // La voix du user a priorité : le guidage en cours se tait aussi.
        voiceNav.stopVoice()
        // Ce qui est filmé devient le contexte de la question : une frame d'une
        // tenue précédente est périmée et gagnerait sinon la place (question
        // portant sur une image figée).
        if (_state.value.recordingVideo) shots.releaseContext()
        shots.captureForHold()
        update { it.copy(holding = true, status = null) }
        haptics.holdStart()
        if (dictation.start()) liveDictation.start(settings)
    }

    /** Doigt relâché : micro coupé, transcription, MP3 en cache, réponse IA parlée. */
    fun onHoldEnd() {
        if (!active) return
        haptics.holdEnd()
        if (!_state.value.holding) return
        liveDictation.stop()
        update { it.copy(holding = false) }
        viewModelScope.launch { transcribeAndAnswer() }
    }

    /** Tenue annulée (app en arrière-plan ou geste perdu) : micro relâché, rien de transcrit. */
    fun onHoldCancel() {
        if (!_state.value.holding) return
        liveDictation.stop()
        dictation.discard()
        haptics.holdEnd()
        update { it.copy(holding = false) }
    }

    /** Tap simple n'importe où : démarre la vidéo (fenêtre flottante), ou la ferme. */
    fun onSingleTap() {
        if (!active) return
        if (_state.value.recordingVideo) cameraOps.stopVideo() else cameraOps.startVideo()
    }

    // ————— Appui long : micro → texte → réponse parlée —————

    /** Fin de la tenue : transcription, coupe d'intro éventuelle, puis réponse IA. */
    private suspend fun transcribeAndAnswer() {
        if (!playerIdle()) {
            // Tenue pendant une lecture : capture jetée, micro jamais bloqué, et dit.
            dropHeard(IseeStatusText.BUSY_SPEAKING)
            return
        }
        // Échec de dictée : le motif est déjà affiché, la conversation reste à l'écran.
        // Le contexte photo de cette tenue est relâché : il ne gèle pas à vie.
        val heard = dictation.finish(settings) ?: run {
            shots.releaseContext()
            return
        }
        if (isIntroDisablePhrase(heard.text)) {
            dropHeard()
            intro.disable()
            return
        }
        if (speedOrder.run(heard.text)) return
        if (runSettingsCommand(heard.text)) return
        handleDictated(heard.text, heard.audio)
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
    fun settingsOpened() {
        update { it.copy(openSettings = false) }
    }

    /** Texte transcrit : coupe d'intro possible, ligne Room + MP3, puis question au LLM. */
    private suspend fun handleDictated(text: String, pcm: ByteArray) {
        if (text.isBlank()) {
            dropHeard(IseeStatusText.NOTHING_HEARD)
            return
        }
        if (_state.value.thinking) {
            dropHeard(IseeStatusText.ANSWER_PENDING)
            return
        }
        if (modes.run(text)) return
        if (steering.runSteering(text)) return
        // Ordre GPS (« arrête / désactive / active le GPS ») : consommé même
        // sans URL Valhalla (guidage Maps), avant le handoff navigation.
        if (voiceNav.runOrder(text)) return
        // Destination (avec ou sans « à », voir NavigationIntent) : l'ordre ne
        // part jamais au LLM ; sans destination comprise, la voix la redemande.
        if (voiceNav.run(text)) return
        if (navigation.onTranscript(text)) return
        // Ordre « traverser » : consommé ici (mode Traversée + voix), jamais une question LLM.
        if (steering.runCrossing(text)) return
        // Ordre navigation sans destination comprise : demande, jamais une description LLM.
        if (navClarify.run(text)) return
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

    private fun canAnnounce(): Boolean {
        val state = _state.value
        val busy = !playerIdle() || state.holding || state.transcribing || state.thinking
        return !busy && voiceNavVoiceGate.ambientAllowed()
    }

    /** Décision d'une voix ambiante (annonce ou voix du sol), urgence mise à part. */
    private fun maySpeakAmbient(urgent: Boolean): Boolean {
        val state = _state.value
        val busy = !playerIdle() || state.holding || state.transcribing || state.thinking
        return voiceNavVoiceGate.maySpeak(urgent, busy)
    }

    /** GPS désactivé (ordre) : la fenêtre d'action (photo, caméra) réouvre la voix. */
    private val voiceNavVoiceGate get() = voiceNav.voiceGate

    private fun isRouteSpeaking(): Boolean = runCatching { voiceNav.isRouteSpeaking() }.getOrDefault(false)

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

    /** L'app part derrière : minuteur armé (option C), sonde arrêtée net (carte éteinte). */
    fun onBackground() {
        onHoldCancel()
        session.onBackground()
        probe.onBackground()
        probe.stop()
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
