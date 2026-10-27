package dev.sam3vision.app.isee

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isIntroDisablePhrase
import dev.sam3vision.app.media.CacheCleanerManager
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MediaDatabase
import dev.sam3vision.app.navigation.AndroidNavigationLauncher
import dev.sam3vision.app.seededFromBuild
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speechClient
import dev.sam3vision.app.isee.vm.IseeAnswers
import dev.sam3vision.app.isee.vm.IseeCameraCoordinator
import dev.sam3vision.app.isee.vm.IseeDictation
import dev.sam3vision.app.isee.vm.IseeHoldMic
import dev.sam3vision.app.isee.vm.IseeLiveDictation
import dev.sam3vision.app.isee.vm.IseePhotoShot
import dev.sam3vision.app.isee.vm.IseeStatusText
import dev.sam3vision.app.isee.vm.IseeTranscript
import dev.sam3vision.app.isee.vm.IseeVoice
import dev.sam3vision.app.isee.vm.NavigationHook
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.crossing.CrossingVoiceAction
import dev.sam3vision.core.crossing.CrossingVoiceCommand
import dev.sam3vision.core.live.SteeringCommand
import dev.sam3vision.core.live.SteeringText
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SettingsVoiceCommand
import dev.sam3vision.core.speech.TtsSpeedCommand
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private val player = SpeechPlayer(
        dir = File(app.cacheDir, "isee-play"),
        onComplete = {
            // Fin de la lecture : les sous-titres disparaissent aussitôt.
            update { st -> st.copy(speakingEntryId = null, speakingText = "") }
        },
        onPlaybackError = { update { st -> st.copy(speakingEntryId = null, speakingText = "") } },
    )

    private val _state = MutableStateFlow(IseeUiState())
    val state: StateFlow<IseeUiState> = _state

    /** Fermeture / arrière-plan : purge et minuteur, hors de la vie du ViewModel. */
    private val session = IseeSession(cleaner, player, ::update)

    /** SAM pendant l'enregistrement : masques, distances, latence et phrase à dire. */
    val probe = LiveProbe(viewModelScope)

    /** Voix de proximité : dit les objets et leur distance une fois par scène. */
    private val proximity = ProximityVoice(player, { settings }, ::speakAnnouncement, ::canAnnounce)

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
        onRecordingStarted = { probe.begin(); proximity.reset() },
        prune = ::enforceFifo,
    )

    /** Question dictée → réponse du LLM → texte et voix. */
    private val answers = IseeAnswers(
        dao = dao,
        media = media,
        settings = { settings },
        update = ::update,
        transcript = transcript,
        synthesize = { text -> voice.synthesize(text) },
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

    init {
        // Le texte d'intro est le premier message une fois pour toutes.
        update { it.copy(introVisible = true) }
        viewModelScope.launch {
            // Réouverture complète : rien d'une session précédente ne survit. La purge
            // est aussi faite ici, car un force-stop ou un balayage des apps récentes
            // ne passe pas par onDestroy — le téléphone gardait alors les messages.
            runCatching { cleaner.purgeAll() }
            settings = seededFromBuild(prefs.load())
            syncProbe()
            transcript.refresh()
            intro.playIfPending()
        }
        probe.observeSpoken(viewModelScope, proximity) { canAnnounce() }
        probe.observeCrossing(viewModelScope, haptics)
    }

    /** Bascule du mode Traversée (puce de la carte caméra) : feu+voitures ou retour. */
    fun toggleCrossing() {
        probe.setCrossingActive(settings, !probe.state.value.crossingActive)
    }

    /**
     * Ordre vocal « traverser » : consommé ici, jamais une question LLM.
     * Confirmation parlée par le même chemin que la navigation. Sans effet si
     * le mode est déjà dans l'état demandé (reconfigurer coûte une session GPU).
     */
    private suspend fun runCrossingCommand(text: String): Boolean {
        val command = CrossingVoiceCommand.parse(text) ?: return false
        val wantActive = command == CrossingVoiceAction.Activate
        if (probe.state.value.crossingActive != wantActive) {
            probe.setCrossingActive(settings, wantActive)
        }
        speakNavigation(CrossingText.mode(wantActive, settings.speechLanguage))
        return true
    }

    /**
     * Ordre vocal de cap (« va à gauche ») : le guidage recalcule le passage
     * vers ce cap, la confirmation part par le même chemin que la navigation.
     * Vrai si [text] portait un ordre (la dictée ne part ni à Maps ni au LLM).
     */
    private suspend fun runSteeringCommand(text: String): Boolean {
        val cap = SteeringCommand.parse(text) ?: return false
        probe.setSteering(cap)
        val confirm = SteeringText.confirm(cap, settings.guideStraight, settings.speechLanguage)
        update { it.copy(status = confirm) }
        speakNavigation(confirm)
        return true
    }

    override fun onCleared() {
        liveDictation.stop()
        dictation.discard()
        player.release()
        super.onCleared()
    }

    fun attachCamera(actions: IseeCameraActions) {
        camera = actions
    }

    fun detachCamera() {
        camera = null
    }

    /** Réglages enregistrés depuis l'écran Settings : rechargement brûlant. */
    fun refreshSettings() = viewModelScope.launch {
        settings = seededFromBuild(prefs.load())
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
        if (runSpeedCommand(heard.text)) return
        if (runSettingsCommand(heard.text)) return
        handleDictated(heard.text, heard.audio)
    }

    /**
     * Ordre vocal de vitesse (« vitesse 1,5 », « parle plus vite ») : réglage
     * persisté, lecteur à jour, confirmation dite à la nouvelle vitesse.
     * Vrai si [text] portait un ordre (la dictée ne part pas au LLM).
     */
    private fun runSpeedCommand(text: String): Boolean {
        val speed = TtsSpeedCommand.parse(text, settings.ttsSpeed) ?: return false
        settings = settings.copy(ttsSpeed = speed)
        viewModelScope.launch { runCatching { prefs.save(settings) } }
        player.speed = speed
        val confirmation = TtsSpeedCommand.spoken(speed, settings.speechLanguage)
        dropHeard(confirmation)
        // Sans clé ElevenLabs, le texte reste affiché et la vitesse s'applique
        // aux prochaines lectures : un ordre vocal n'échoue jamais à voix haute.
        viewModelScope.launch {
            val bytes = voice.synthesize(confirmation)
            if (bytes.isNotEmpty()) {
                update { it.copy(speakingEntryId = SPEED_BUBBLE_ID, speakingText = confirmation, status = confirmation) }
                player.play(bytes)
            }
        }
        return true
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
        // Une seule question à la fois : deux appels LLM concurrents sont refusés
        // par le backend (409 « limite atteinte ») et le second restait sans réponse.
        if (_state.value.thinking) {
            dropHeard(IseeStatusText.ANSWER_PENDING)
            return
        }
        // Ordre de cap (« va à gauche ») : consommé ici AVANT la navigation Maps
        // (« va à gauche » matche aussi « va à X ») — guidage + voix, jamais LLM.
        if (runSteeringCommand(text)) return
        // Ordre de navigation : consommé ici (Maps + voix), jamais une question LLM.
        if (navigation.onTranscript(text)) return
        // Ordre « traverser » : consommé ici (mode Traversée + voix), jamais une question LLM.
        if (runCrossingCommand(text)) return
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
        return playerIdle() && !state.holding && !state.transcribing && !state.thinking
    }

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

    /** L'app part derrière : minuteur armé (option C) et envoi SAM de la sonde coupé. */
    fun onBackground() {
        onHoldCancel()
        session.onBackground()
        probe.onBackground()
    }

    /** L'app revient au premier plan : minuteur annulé (option C), sonde réarmée. */
    fun onForeground() {
        session.onForeground()
        probe.onForeground()
    }

}
