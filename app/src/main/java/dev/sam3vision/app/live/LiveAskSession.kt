package dev.sam3vision.app.live

import android.util.Base64
import dev.sam3vision.app.LLM_ABSENT_LINE
import dev.sam3vision.app.LiveUiState
import dev.sam3vision.app.NO_FRAME_LINE
import dev.sam3vision.app.PictoOverlay
import dev.sam3vision.app.LiveVoiceRequest
import dev.sam3vision.app.focusLabel
import dev.sam3vision.app.focusWindow
import dev.sam3vision.app.isee.PICTO_FRAME_EDGE
import dev.sam3vision.app.isee.PICTO_PHOTO_EDGE
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.isee.StoredPicto
import dev.sam3vision.app.isee.decodeFrameGray
import dev.sam3vision.app.isee.vm.IseePicto
import dev.sam3vision.app.timelineContextOrNull
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.live.MaisonEnterCommand
import dev.sam3vision.core.live.MaisonEnterText
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.MetaImageClient
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoVoiceOrder
import dev.sam3vision.core.picto.PictoVoiceText
import dev.sam3vision.core.picto.buildTemplate
import dev.sam3vision.core.picto.matchTemplate
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AskUseCase
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Demandes sur le direct, à l'écrit ou à la voix (« Parler ») : questions LLM
 * (la **dernière image part tout de suite au LLM**, sans dépendre de SAM),
 * chasse SAM (mot maison seul → relance du direct) et ordres picto (« fais
 * un pictogramme de X » → repère armé, « X » → guidage parlé).
 *
 * L'historique de la conversation est conservé d'une question à l'autre —
 * jusqu'à la fin de la séance : [reset] l'oublie, et avec lui le journal
 * ([LiveTimeline]) que ces questions consultent. C'est le point unique où
 * la mémoire du direct s'efface, appelé au démarrage **et** à l'arrêt : sinon
 * l'écran d'un direct arrêté montre encore, et répond encore sur, le direct
 * précédent.
 *
 * Les repères armés (les siens comme ceux de l'écran iSee : même magasin)
 * sont cherchés à 2 Hz et posés en overlay, mais restent **muets** : seule
 * la chasse nommée parle (« Médics à 9 heures »), et les positions SAM se
 * taisent pendant qu'un picto parle ([pictoActive]). La démo (« démo picto »)
 * remplace le repérage par des faux repères fixes, guidables pareil.
 *
 * Les réglages sont relus à chaque question ([reloadSettings]) : une clé saisie pendant
 * le direct s'applique tout de suite. C'est aussi pourquoi la classe ne garde pas de
 * copie des réglages — elle lit [settings], la source unique du ViewModel.
 */
class LiveAskSession(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LiveUiState>,
    private val timeline: LiveTimeline,
    /** Dernière frame gardée pour les questions ("" tant qu'aucune). */
    private val latestJpeg: () -> String,
    /** Relit **et applique** les réglages chiffrés (le ViewModel garde la source unique). */
    private val refreshSettings: suspend () -> Unit,
    private val settings: () -> Settings,
    private val llm: (Settings) -> LlmClient,
    /** Fenêtre du journal envoyée au LLM : 30 s suffisent pour « il y a 5 s ». */
    private val windowMs: Long,
    /** Chasse SAM d'un mot maison seul : relance le direct, jamais le LLM. */
    private val restart: (List<String>) -> Unit = {},
    /** Dossier des repères : le magasin est partagé avec l'écran iSee. */
    private val pictoDir: () -> File = { File("") },
    /** Voix des positions, partagée : une seule phrase à la fois sur le direct. */
    private val pictoSpeak: suspend (Settings, String) -> Unit = { _, _ -> },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pictoClient: (Settings) -> PictoImageClient =
        { s -> MetaImageClient(s.asrBaseUrl, s.asrApiKey) },
    private val store: PictoStore = PictoStore(pictoDir()),
    private val frameGray: (String, Int) -> GrayImage? = ::decodeFrameGray,
    private val decodeB64: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) },
    private val guideAnnouncer: PositionAnnouncer = PositionAnnouncer(),
) {

    /** Historique envoyé au modèle, remis à zéro à chaque nouvelle séance. */
    private var history: List<ChatTurn> = emptyList()

    /** Repères armés (rechargés à chaque séance + après chaque création). */
    @Volatile
    private var pictos: List<StoredPicto> = emptyList()

    /** Création en vol : une seule à la fois, la suivante est dite puis jetée. */
    @Volatile
    private var pictoBusy = false

    /** Mot guidé (`null` = pas de guidage : les repères restent muets). */
    @Volatile
    private var pictoHunt: String? = null

    /** Label à créer dès la première frame (ordre iSee), `null` une fois lancé. */
    @Volatile
    private var pendingCreate: String? = null

    /** Démo active : les faux repères remplacent le repérage (overlay + guidage). */
    @Volatile
    private var pictoDemo = false

    /** Génération du tour : une séance neuve fait taire les retours tardifs. */
    @Volatile
    private var pictoTurn = 0

    /** Dernier passage du matcher (2 Hz comme l'écran iSee). */
    private var lastPictoMatchAt = 0L

    /** Une phrase picto à la fois : la suivante est jetée, pas mise en file. */
    private val pictoSpeaking = Mutex()

    /** Le picto parle ou va parler : les positions SAM se taisent (pas de chevauchement). */
    val pictoActive: Boolean get() = pictoBusy || pictoHunt != null

    /** PNG d'un repère armé pour l'overlay, `null` si inconnu (démo, faux repères). */
    fun pngOf(id: String): ByteArray? = pictos.firstOrNull { it.id == id }?.png

    /** Fin de séance (démarrage suivant **ou** arrêt) : conversation et journal repartent de zéro. */
    fun reset() {
        history = emptyList()
        timeline.clear()
        pictoHunt = null
        pictoDemo = false
        pendingCreate = null
        pictoTurn++
        state.update { it.copy(pictoOverlay = null) }
        scope.launch(io) { pictos = store.list() }
    }

    /**
     * Question posée à l'écrit ou à la voix (« Parler ») : une seule à la fois.
     * Ordre de navigation (« je veux aller à X », « aller X ») → écran de
     * navigation, ou redemande de la destination si elle manque ; ordre picto
     * (« fais un pictogramme de X ») → création ; mot visant un repère armé →
     * guidage ; mot maison seul → chasse SAM ; sinon LLM.
     */
    fun ask(question: String) {
        if (LiveNavOrders.handle(question, settings(), scope, pictoSpeak)) return
        if (MaisonEnterCommand.isEnterOrder(question)) {
            enterHome()
            return
        }
        if (PictoVoiceOrder.isDemoToggle(question)) {
            toggleDemo()
            return
        }
        PictoVoiceOrder.parse(question)?.let { createPicto(it); return }
        pictoHunt = null
        val hunted = huntWord(question)
        if (hunted != null) {
            pictoHunt = hunted
            guideAnnouncer.reset()
            return
        }
        val hunt = HomeModes.voiceConcept(question, state.value.prompts, state.value.samEnabled)
        if (hunt != null) {
            restart(listOf(hunt))
            return
        }
        val text = question.trim()
        if (text.isEmpty() || state.value.memory.asking) return
        val jpeg = latestJpeg()
        if (jpeg.isBlank()) {
            state.update { it.copy(memory = it.memory.copy(error = NO_FRAME_LINE)) }
            return
        }
        state.update {
            it.copy(memory = it.memory.copy(asking = true, question = text, answer = null, error = null))
        }
        scope.launch {
            refreshSettings()
            if (!settings().llmConfigured) {
                state.update { it.copy(memory = it.memory.copy(asking = false, error = LLM_ABSENT_LINE)) }
                return@launch
            }
            askWithContext(text, jpeg, state.value.memory.focusSeconds)
        }
    }

    /** Mot de [question] qui vise un repère armé (ou de démo), `null` si aucun. */
    private fun huntWord(question: String): String? {
        val labels = pictos.map { it.label } +
            if (pictoDemo) PictoVoiceOrder.demoHits().map { it.label } else emptyList()
        return PictoVoiceOrder.huntWord(question, labels)
    }

    /** Ordre « maison » : le direct maison (Essentiels), confirmation dite dans tous les cas. */
    private fun enterHome() {
        val language = settings().speechLanguage
        if (HomeModes.isHome(state.value.prompts)) {
            speakPicto(MaisonEnterText.already(language))
        } else {
            restart(HomeModes.DEFAULT.prompts)
            speakPicto(MaisonEnterText.entered(language))
        }
    }

    /** Ordres venus de l'appui long iSee : chasse, démo ou création en attente de frame. */
    fun applyVoiceRequest(request: LiveVoiceRequest.Request) {
        request.pictoHunt?.let {
            pictoHunt = it
            guideAnnouncer.reset()
        }
        if (request.pictoDemo && !pictoDemo) toggleDemo()
        request.pictoCreate?.let { pendingCreate = it }
    }

    /** Bascule de la démo : faux repères affichés (et guidables), état dit à voix haute. */
    private fun toggleDemo() {
        pictoDemo = !pictoDemo
        if (!pictoDemo) state.update { it.copy(pictoOverlay = null) }
        speakPicto(PictoVoiceText.demo(pictoDemo, settings().speechLanguage))
    }

    /** Ordre « fais un pictogramme de X » : frame gardée → Muse → repère armé, sans relecture. */
    private fun createPicto(label: String) {
        if (pictoBusy) {
            speakPicto(PictoVoiceText.busy(settings().speechLanguage))
            return
        }
        val jpeg = latestJpeg()
        if (jpeg.isBlank()) {
            speakPicto(PictoVoiceText.failed(settings().speechLanguage))
            return
        }
        pictoBusy = true
        val turn = pictoTurn
        speakPicto(PictoVoiceText.started(settings().speechLanguage))
        scope.launch(io) {
            val done = runCatching {
                val photo = decodeB64(jpeg)
                val gray = frameGray(jpeg, PICTO_PHOTO_EDGE) ?: throw IllegalStateException("frame illisible")
                val png = pictoClient(settings()).editToPictogram(photo, label).getOrThrow()
                store.save(label, png, buildTemplate(gray))
            }
            pictos = runCatching { store.list() }.getOrElse { pictos }
            pictoBusy = false
            if (turn != pictoTurn) return@launch
            val language = settings().speechLanguage
            if (done.isSuccess) {
                val created = if (label.isBlank()) PictoVoiceText.createdUnnamed(language)
                else PictoVoiceText.created(label, language)
                speakPicto(created)
            } else {
                speakPicto(PictoVoiceText.failed(language))
            }
        }
    }

    /**
     * Frame du direct (2 Hz) : les repères armés y sont cherchés, l'overlay
     * posé dans tous les cas, la voix ne guide que le mot chassé.
     */
    fun onLiveFrame(width: Int, height: Int, nowMs: Long = System.currentTimeMillis()) {
        pendingCreate?.let { label ->
            if (latestJpeg().isNotBlank()) {
                pendingCreate = null
                createPicto(label)
            }
        }
        if (nowMs - lastPictoMatchAt < IseePicto.MATCH_EVERY_MS) return
        lastPictoMatchAt = nowMs
        if (pictoDemo) {
            scope.launch { matchDemo(width, height, nowMs) }
            return
        }
        val known = pictos
        if (known.isEmpty()) return
        scope.launch(io) {
            val frame = frameGray(latestJpeg(), PICTO_FRAME_EDGE) ?: return@launch
            val hits = known.mapNotNull { matchTemplate(frame, it.template, it.id, it.label) }
            state.update { it.copy(pictoOverlay = PictoOverlay(width, height, hits)) }
            guide(hits, nowMs)
        }
    }

    /** Démo : les faux repères remplacent le repérage (overlay + guidage testables sans caméra). */
    private suspend fun matchDemo(width: Int, height: Int, nowMs: Long) {
        val hits = PictoVoiceOrder.demoHits()
        state.update { it.copy(pictoOverlay = PictoOverlay(width, height, hits)) }
        guide(hits, nowMs)
    }

    /** Guidage : les détections du mot chassé parlent, les autres restent muettes. */
    private suspend fun guide(hits: List<PictoHit>, nowMs: Long) {
        val hunt = pictoHunt ?: return
        val targets = hits.filter { PictoVoiceOrder.matches(hunt, it.label) }
        if (targets.isEmpty()) return
        val placed = targets.map { PlacedLabel(it.label, it.x + it.w / 2f, x0 = it.x, x1 = it.x + it.w) }
        val phrase = guideAnnouncer.onFrame(nowMs, placed, settings().speechLanguage) ?: return
        state.update { it.copy(spoken = phrase) }
        if (!pictoSpeaking.tryLock()) return
        try {
            pictoSpeak(settings(), phrase)
        } finally {
            pictoSpeaking.unlock()
        }
    }

    /** Dit [phrase] (voix des positions) + sous-titre ; jetée si une phrase picto joue. */
    private fun speakPicto(phrase: String) {
        state.update { it.copy(spoken = phrase) }
        scope.launch {
            if (!pictoSpeaking.tryLock()) return@launch
            try {
                pictoSpeak(settings(), phrase)
            } finally {
                pictoSpeaking.unlock()
            }
        }
    }

    /** Envoi au LLM : image + journal (s'il y a des pistes), puis mise à jour de l'état. */
    private suspend fun askWithContext(question: String, jpegB64: String, focusSeconds: Int) {
        val now = System.currentTimeMillis()
        val global = timeline.recent(now, windowMs)
        val window = focusWindow(now, focusSeconds)
        val focus = timeline.summary(window.first, window.last)
        val context = timelineContextOrNull(
            global,
            focus,
            focusLabel(focusSeconds, settings().speechLanguage),
            settings().speechLanguage,
        )
        val useCase = AskUseCase(llm(settings()))
        useCase.invoke(jpegB64, question, history, context).fold(
            onSuccess = { answer ->
                history = useCase.nextHistory(history, question, answer)
                state.update { it.copy(memory = it.memory.copy(asking = false, answer = answer)) }
            },
            onFailure = { error ->
                state.update {
                    it.copy(memory = it.memory.copy(asking = false, error = error.message ?: "answer impossible"))
                }
            },
        )
    }
}
