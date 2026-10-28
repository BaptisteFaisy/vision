package dev.sam3vision.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.vm.AppAnalysis
import dev.sam3vision.app.vm.AppChat
import dev.sam3vision.app.vm.AppDiagnostics
import dev.sam3vision.app.vm.AppImageLoader
import dev.sam3vision.app.vm.AppStatusText
import dev.sam3vision.app.vm.AppVoice
import dev.sam3vision.app.vm.StateUpdate
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.mask.colorizedMaskBitmap
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.MASK_ENCODING_PNG
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Étape de la vue : idle, analyse en cours, prêt, ou échec avec message lisible. */
sealed interface Phase {
    data object Idle : Phase
    data object Analyzing : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

/** État complet observé par les écrans Compose. */
data class UiState(
    val photo: Bitmap? = null,
    val photoB64: String = "",
    val analysis: Analysis? = null,
    val overlays: List<Bitmap> = emptyList(),
    val showMasks: Boolean = true,
    val phase: Phase = Phase.Idle,
    val segmentationActive: Boolean = false,
    val segmentationNote: String? = null,
    val chat: List<ChatTurn> = emptyList(),
    val asking: Boolean = false,
    val settings: Settings = Settings.Default,
    val settingsReady: Boolean = false,
    val statusLine: String? = null,
    val dictation: DictationState = DictationState(),
    val speak: SpeakState = SpeakState.Idle,
) {
    val objects: List<SegObject> get() = analysis?.objects.orEmpty()
    val canAsk: Boolean get() = photoB64.isNotBlank() && settings.llmConfigured && !asking
    val hasPhoto: Boolean get() = photo != null
}

/**
 * Le chef d'orchestre de l'app principale : capture et analyse d'une photo, chat sur la
 * photo, réglages, dictée, voix. Il ne fait que **conduire** — l'état d'écran ci-dessus,
 * le cycle de vie, et le branchement des collaborateurs :
 *
 * - [AppImageLoader] : décodage réduit au bord max + encodage base64 de la photo ;
 * - [AppAnalysis] : SAM + LLM, masques, note de segmentation, annonce des positions ;
 * - [AppChat] : question → réponse → historique du chat ;
 * - [AppVoice] : synthèse ElevenLabs et lecture d'une phrase à la fois ;
 * - [AppDiagnostics] : « Tester LLM / SAM / dictée » et extinction de la GPU ;
 * - [DictationFeature] : micro, bandeaux et repli de transcription.
 *
 * Les textes affichés vivent dans [AppStatusText], les tests dans `app/src/test/…/vm/`.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store: SettingsStore = SettingsStoreFactory.create(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Mise à jour d'état vue par les collaborateurs (`StateUpdate`, comme côté iSee). */
    private fun update(block: (UiState) -> UiState) = _state.update(block)
    private val stateUpdate: StateUpdate = ::update

    /** Lecteur audio (MediaPlayer) dans le cache privé : un seul fichier à la fois. */
    private val speech: SpeechPlayer by lazy {
        SpeechPlayer(File(getApplication<Application>().cacheDir, "tts"), speaker::onFinished, speaker::onError)
    }

    /** Voix ElevenLabs des bandeaux de dictée (aucun TTS système dans l'app). */
    private val voice = VoiceAnnouncer(app)

    /** Appui long + bouton « Dicter » : toute la mécanique est dans [DictationFeature]. */
    private val dictation = DictationFeature(app, _state, viewModelScope) { settings -> speechClient(settings) }

    /**
     * Photo : décodage réduit à [PHOTO_SEND_EDGE], puis JPEG base64. Le serveur
     * travaille à 640 px max de toute façon : envoyer 1024 ne donnait que des
     * octets (+60 % d'upload), jamais de précision. Affichage et envoi partagent
     * le même bitmap, donc boîtes et positions restent cohérentes.
     */
    private val loader = AppImageLoader<Bitmap>(
        decode = { file, edge -> decodeScaledExact(file, edge) },
        encode = { bitmap -> bitmapToJpegBase64(bitmap) },
        maxEdge = PHOTO_SEND_EDGE,
    )

    /** Voix du chat et de l'analyse : synthèse `:core`, lecture par le `SpeechPlayer`. */
    private val speaker = AppVoice(
        settings = { _state.value.settings },
        update = stateUpdate,
        scope = viewModelScope,
        play = { bytes ->
            speech.speed = _state.value.settings.ttsSpeed
            speech.play(bytes)
        },
        stopPlayback = { speech.stop() },
    )

    /** Analyse d'une photo : SAM puis LLM, masques coloriés et positions annoncées. */
    private val analysis = AppAnalysis(
        state = { _state.value },
        update = stateUpdate,
        llm = { settings -> llmClient(settings) },
        sam = ::samClientOrNull,
        overlays = ::buildOverlays,
        announce = ::announcePositions,
    )

    /** Chat sur la photo courante. */
    private val chat = AppChat(
        state = { _state.value },
        update = stateUpdate,
        llm = { settings -> llmClient(settings) },
        speak = ::speak,
    )

    /** Les trois tests des Réglages et l'extinction GPU de la fermeture. */
    private val diagnostics = AppDiagnostics(
        state = { _state.value },
        update = stateUpdate,
        llm = { settings -> llmClient(settings) },
        speech = { settings -> speechClient(settings) },
    )

    init {
        viewModelScope.launch { _state.update { it.copy(settings = seededFromBuild(store.load()), settingsReady = true) } }
        // Une seule règle : chaque nouveau bandeau est lu à voix haute (ElevenLabs).
        viewModelScope.launch {
            _state
                .map { it.dictation.overlay?.sequence to it.dictation.overlay?.spoken }
                .distinctUntilChanged()
                .collect { (_, spoken) ->
                    if (spoken != null) launch { voice.say(_state.value.settings, spoken) }
                }
        }
    }

    override fun onCleared() {
        speaker.cancel()
        speech.release()
        voice.release()
        super.onCleared()
    }

    /** Une photo capturée ou choisie : on l'affiche tout de suite, puis on l'analyse. */
    fun onPhoto(file: File) = viewModelScope.launch {
        val loaded = loader.load(file)
        if (loaded == null) {
            update { it.copy(phase = Phase.Failed(AppStatusText.imageUnreadable(file))) }
            return@launch
        }
        _state.update {
            UiState(
                photo = loaded.image,
                photoB64 = loaded.base64,
                showMasks = it.showMasks,
                settings = it.settings,
                settingsReady = it.settingsReady,
                phase = Phase.Analyzing,
            )
        }
        analysis.analyze(loaded.base64)
    }

    /**
     * Dit à voix haute où sont les objets segmentés (« Personne à gauche, chaise au centre »).
     * Même bascule que le direct, même voix ElevenLabs ; silencieux sans objet ou sans clé.
     */
    private fun announcePositions(objects: List<SegObject>, settings: Settings) {
        val photo = _state.value.photo
        if (!settings.announcePositions || photo == null) return
        val placed = objects.mapNotNull {
            PositionText.fromDetection(Detection(it.label, it.score, it.box, photo.width, photo.height))
        }
        val phrase = PositionText.phrase(placed, language = settings.speechLanguage) ?: return
        viewModelScope.launch { voice.say(settings, phrase) }
    }

    /** Masques coloriés dans les bornes de la photo ; aucun masque lisible ⇒ liste vide. */
    private suspend fun buildOverlays(objects: List<SegObject>): List<Bitmap> {
        if (_state.value.photo == null) return emptyList()
        return withContext(Dispatchers.Default) {
            // Même helper que le direct : décodage selon l'encodage, coloriage par table.
            objects.mapIndexedNotNull { index, obj ->
                colorizedMaskBitmap(obj.maskPngB64, MASK_ENCODING_PNG, maskArgb(index), MASK_ALPHA)
            }
        }
    }

    private fun maskArgb(index: Int): Int = maskColorAt(index).toArgb()

    fun ask(question: String) = viewModelScope.launch { chat.ask(question) }

    fun retryAnalysis() = viewModelScope.launch {
        val encoded = _state.value.photoB64
        if (encoded.isNotBlank()) {
            update { it.copy(phase = Phase.Analyzing) }
            analysis.analyze(encoded)
        }
    }

    /** Le mode direct alimente le chat sans passer par une capture. */
    fun useLiveFrame(jpegB64: String) {
        if (jpegB64.isBlank()) return
        _state.update { it.copy(photoB64 = jpegB64) }
    }

    fun toggleMasks() = _state.update { it.copy(showMasks = !it.showMasks) }

    fun saveSettings(next: Settings) = viewModelScope.launch {
        val cleaned = withTtsDefaults(next).copy(maxObjects = next.objectsOnScreen)
        store.save(cleaned)
        _state.update { it.copy(settings = cleaned, statusLine = AppStatusText.SETTINGS_SAVED) }
    }

    fun testLlm() = viewModelScope.launch { diagnostics.testLlm() }

    /** Extinction volontaire du conteneur GPU à la fermeture de l'app. */
    fun releaseGpu() = diagnostics.releaseGpu()

    /** Réveil silencieux de la GPU à l'ouverture de l'app (`GET /health`). */
    fun warmGpu() = viewModelScope.launch { diagnostics.warmGpu() }

    fun testSam() = viewModelScope.launch { diagnostics.testSam() }

    // ————— Dictée (appui long partout, bouton « Dicter » dans le chat) —————

    /** Bouton « Dicter » du chat : ouvre le micro jusqu'au prochain Stop. */
    fun startDictation() = dictation.start(hold = false)

    fun stopDictation() = dictation.stop(hold = false)

    /** Appui long maintenu sur la surface de l'app : le micro s'ouvre, sans limite. */
    fun onHoldStart() = dictation.start(hold = true)

    /** Doigt relâché : coupe le micro et envoie l'audio au moteur de dictée. */
    fun onHoldEnd() = dictation.stop(hold = true)

    /** Permission micro refusée au lancement : bandeau d'erreur, lu à la voix. */
    fun onMicDenied() = dictation.onMicDenied()

    fun dismissOverlay() = dictation.dismissOverlay()

    /** Vérifie le moteur de dictée avec 1 s de silence : seule la connexion est testée. */
    fun testAsr() = viewModelScope.launch { diagnostics.testAsr() }

    // ————— Voix ElevenLabs (chat, analyse, réglages) —————

    /** Lit [text] à voix haute via ElevenLabs ; une seule phrase active à la fois. */
    fun speak(text: String) = speaker.speak(text)

    /** Bouton « Tester la voix » des réglages. */
    fun testVoice() = speaker.testVoice()

    fun stopSpeaking() = speaker.stop()

    fun reset() {
        stopSpeaking()
        dictation.abort()
        _state.update { UiState(settings = it.settings, settingsReady = it.settingsReady) }
    }

    companion object {
        /** Bord max envoyé : le serveur travaille à 640 px, inutile d'envoyer plus. */
        const val PHOTO_SEND_EDGE = 640
    }
}