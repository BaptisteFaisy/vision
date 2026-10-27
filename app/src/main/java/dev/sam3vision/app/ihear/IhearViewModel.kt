package dev.sam3vision.app.ihear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.IhearHaptics
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isIntroDisablePhrase
import dev.sam3vision.app.media.CacheCleanerManager
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MediaDatabase
import dev.sam3vision.app.navigation.AndroidNavigationLauncher
import dev.sam3vision.app.seededFromBuild
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speechClient
import dev.sam3vision.app.ihear.vm.IhearAnswers
import dev.sam3vision.app.ihear.vm.IhearCameraCoordinator
import dev.sam3vision.app.ihear.vm.IhearDictation
import dev.sam3vision.app.ihear.vm.IhearHoldMic
import dev.sam3vision.app.ihear.vm.IhearPhotoShot
import dev.sam3vision.app.ihear.vm.IhearStatusText
import dev.sam3vision.app.ihear.vm.IhearTranscript
import dev.sam3vision.app.ihear.vm.IhearVoice
import dev.sam3vision.app.ihear.vm.NavigationHook
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.crossing.CrossingVoiceAction
import dev.sam3vision.core.crossing.CrossingVoiceCommand
import dev.sam3vision.core.live.SteeringCommand
import dev.sam3vision.core.live.SteeringText
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.TtsSpeedCommand
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Le chef d'orchestre de l'écran unique (chat, caméra dans une bulle : vidéo du tap,
 * photo du double tap) : il tient l'état affiché et branche les collaborateurs —
 * [IhearTranscript] (bulles et historique), [IhearDictation] (micro et transcription),
 * [IhearAnswers] (question → réponse → voix), [IhearPhotoShot] (photo et pastilles),
 * [IhearCameraCoordinator] (vidéo), plus la lecture, l'intro et la sonde déjà extraites.
 *
 * Lui-même ne fait que conduire : gestes, cycle de vie, et réglages lus une fois.
 */
class IhearViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SettingsStore = SettingsStoreFactory.create(app)
    private val dao = MediaDatabase.get(app).messages()
    private val media = MediaCacheStore(app, dao)
    private val cleaner = CacheCleanerManager(dao, media.dir())
    private val haptics = IhearHaptics(app)

    private val player = SpeechPlayer(
        dir = File(app.cacheDir, "ihear-play"),
        onComplete = {
            // L'intro reste posée à l'écran : seule la coupure par la voix la retire.
            update { st -> st.copy(speakingEntryId = null) }
        },
        onPlaybackError = { update { st -> st.copy(speakingEntryId = null) } },
    )

    private val _state = MutableStateFlow(IhearUiState())
    val state: StateFlow<IhearUiState> = _state

    /** Fermeture / arrière-plan : purge et minuteur, hors de la vie du ViewModel. */
    private val session = IhearSession(cleaner, player, ::update)

    /** SAM pendant l'enregistrement : masques, distances, latence et phrase à dire. */
    val probe = LiveProbe(viewModelScope)

    /** Voix de proximité : dit les objets et leur distance une fois par scène. */
    private val proximity = ProximityVoice(player) { settings }

    /** Voix de l'intro (le texte écrit, lui, ne part jamais). */
    private val intro = IhearIntro(getApplication(), player, { settings }, { settings = it }, prefs, ::update)

    /** Bulles du chat et tours d'historique, reconstruits depuis Room. */
    private val transcript = IhearTranscript(dao, ::update)

    /** Voix ElevenLabs d'un texte de bulle : octets vides si rien à dire. */
    private val voice = IhearVoice({ settings }, ::update)

    /** Lecture des bulles, extraite pour garder ce fichier sous 500 lignes. */
    private val playback = IhearPlayback(
        dao = dao,
        media = media,
        player = player,
        state = { _state.value },
        update = ::update,
        synthesize = { text -> voice.synthesize(text) },
        replayIntro = { intro.replay() },
    )

    /** Photo du double tap et contexte image des questions : gel, fenêtre, pastilles. */
    private val shots = IhearPhotoShot(
        media = media,
        camera = { camera },
        state = { _state.value },
        settings = { settings },
        update = ::update,
        scope = viewModelScope,
    )

    /** Micro de l'appui long, transcription incluse. */
    private val dictation = IhearDictation(IhearHoldMic(), ::speechClient, ::update)

    /** Vidéo d'environnement du tap, et lien aux actions de la caméra. */
    private val cameraOps = IhearCameraCoordinator(
        media = media,
        camera = { camera },
        update = ::update,
        scope = viewModelScope,
        onRecordingStarted = { probe.begin(); proximity.reset() },
        prune = ::enforceFifo,
    )

    /** Question dictée → réponse du LLM → texte et voix. */
    private val answers = IhearAnswers(
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

    /** Caméra de la fenêtre flottante, câblée par le composable à l'attachement. */
    var camera: IhearCameraActions? = null

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
        probe.observeSpoken(viewModelScope, proximity) { playerIdle() }
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
        player.release()
        super.onCleared()
    }

    fun attachCamera(actions: IhearCameraActions) {
        camera = actions
        shots.setLens(actions.lensSpec)
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
        // Ce qui est filmé devient le contexte de la question : la photo gelée d'avant
        // est périmée et gagnerait sinon la place (question portant sur une image figée).
        if (_state.value.recordingVideo) shots.releaseContext()
        shots.captureForHold()
        update { it.copy(holding = true, status = null) }
        haptics.holdStart()
        dictation.start()
    }

    /** Doigt relâché : micro coupé, transcription, MP3 en cache, réponse IA parlée. */
    fun onHoldEnd() {
        if (!active) return
        haptics.holdEnd()
        if (!_state.value.holding) return
        update { it.copy(holding = false) }
        viewModelScope.launch { transcribeAndAnswer() }
    }

    /** Tenue annulée (app en arrière-plan ou geste perdu) : micro relâché, rien de transcrit. */
    fun onHoldCancel() {
        if (!_state.value.holding) return
        dictation.discard()
        haptics.holdEnd()
        update { it.copy(holding = false) }
    }

    /** Balayage vertical commencé : vibration tant que le doigt glisse, sauf à bulle unique. */
    fun onVerticalDragStart() {
        if (!active) return
        if (!hasBubbleChoice(bubbleCount(_state.value.entries.size))) return
        haptics.verticalSwipeStart()
    }

    /** Début du glissement vertical : la marche se joue ici (un geste = une bulle), pas
     * au relâchement — un balayage court emporte son effet. */
    fun onVerticalDragMove(downward: Boolean) {
        if (!active) return
        if (_state.value.recordingVideo) return
        viewModelScope.launch {
            val current = _state.value
            // L'intro compte comme une bulle : on peut y revenir pour la relire.
            val total = bubbleCount(current.entries.size)
            when (val step = swipeStep(total, current.selected, current.firstSwipeDownDone, downward)) {
                is SwipeStep.Move -> {
                    update { it.copy(selected = step.selected) }
                    playback.playSelected()
                }
                is SwipeStep.Replay -> {
                    update { it.copy(firstSwipeDownDone = step.firstSwipeDownDone) }
                    playback.playSelected()
                }
                // Bulle unique ou bord atteint : pas de vibration, relecture seule.
                SwipeStep.NoMove -> {
                    if (!hasBubbleChoice(total)) playback.playSelected()
                }
            }
        }
    }

    /** Relâchement : la marche a déjà été jouée au début du glissement ; on coupe
     * juste l'haptique — même en dehors d'iSee, pour ne pas laisser la vibration collée. */
    fun onVerticalSwipeEnd(committed: Boolean, downward: Boolean) {
        haptics.verticalSwipeEnd()
        if (!active) return
    }

    /** Tap simple n'importe où : démarre la vidéo (fenêtre flottante), ou la ferme. */
    fun onSingleTap() {
        if (!active) return
        if (_state.value.recordingVideo) cameraOps.stopVideo() else cameraOps.startVideo()
    }

    /** Double tap rapide : photo immédiate (texture, flash, évaporation en fenêtre). */
    fun onDoubleTap() {
        if (!active || _state.value.recordingVideo) return
        shots.takePhoto()
    }

    // ————— Appui long : micro → texte → réponse parlée —————

    /** Fin de la tenue : transcription, coupe d'intro éventuelle, puis réponse IA. */
    private suspend fun transcribeAndAnswer() {
        if (!playerIdle()) {
            // Tenue pendant une lecture : capture jetée, micro jamais bloqué, et dit.
            dropHeard(IhearStatusText.BUSY_SPEAKING)
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
                update { it.copy(speakingEntryId = SPEED_BUBBLE_ID, status = confirmation) }
                player.play(bytes)
            }
        }
        return true
    }

    /** Texte transcrit : coupe d'intro possible, ligne Room + MP3, puis question au LLM. */
    private suspend fun handleDictated(text: String, pcm: ByteArray) {
        if (text.isBlank()) {
            dropHeard(IhearStatusText.NOTHING_HEARD)
            return
        }
        // Une seule question à la fois : deux appels LLM concurrents sont refusés
        // par le backend (409 « limite atteinte ») et le second restait sans réponse.
        if (_state.value.thinking) {
            dropHeard(IhearStatusText.ANSWER_PENDING)
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

    private fun update(fn: (IhearUiState) -> IhearUiState) = _state.update(fn)

    private fun playerIdle(): Boolean = _state.value.speakingEntryId == null

    /** Annonce de navigation : même chemin audio que les bulles (ElevenLabs + lecteur partagé). */
    private suspend fun speakNavigation(text: String): Unit {
        player.play(voice.synthesize(text))
    }

    /** Sécurité FIFO silencieuse après chaque écriture de fichier multimédia. */
    private suspend fun enforceFifo() {
        runCatching { cleaner.enforceFifoAfterWrite() }
    }

    /** Purge totale à la fermeture de l'activité (option A) : table + cache. */
    fun onAppClosed() = session.onClosed()

    /** L'app part derrière : minuteur d'inactivité de 30 min armé (option C). */
    fun onBackground() = session.onBackground()

    /** L'app revient au premier plan : minuteur annulé (option C). */
    fun onForeground() = session.onForeground()

    companion object {
        const val CHAT_HISTORY = IhearTranscript.CHAT_HISTORY

        /** Une photo du double tap reste à l'écran avant de s'évaporer. */
        const val PHOTO_SHOW_MS = IhearPhotoShot.SHOW_MS

        /** Photo portant des pastilles de distance : laissée plus longtemps, le temps de lire. */
        const val PHOTO_MEASURED_MS = IhearPhotoShot.MEASURED_MS
    }
}