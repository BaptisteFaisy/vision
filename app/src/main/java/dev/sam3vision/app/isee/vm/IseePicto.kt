package dev.sam3vision.app.isee.vm

import android.graphics.RectF
import android.os.SystemClock
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.isee.StoredPicto
import dev.sam3vision.app.isee.decodeFrameGray
import dev.sam3vision.app.isee.photoToJpeg
import dev.sam3vision.app.isee.templateFromPhoto
import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.LiveProbeState
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.picto.PictoVoiceOrder
import dev.sam3vision.core.picto.matchTemplate
import dev.sam3vision.core.settings.Settings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Où en est le repère : repos, photo, ou génération réseau. */
enum class PictoPhase { Idle, Shooting, Generating }

/**
 * L'état picto de l'écran : la phase, les objets reconnus sur la vidéo
 * (overlay temps réel) et le nombre de repères armés.
 */
data class PictoUiState(
    val phase: PictoPhase = PictoPhase.Idle,
    val hits: List<PictoHit> = emptyList(),
    val armed: Int = 0,
)

/**
 * Les repères visuels : photo de l'objet filmé → pictogramme Muse →
 * stockage direct → reconnaissance temps réel sur la vidéo. Ni confirmation,
 * ni nom, ni avant-plan : le pictogramme généré est armé aussitôt et
 * l'overlay le montre à son emplacement sur l'image. Rien ici ne parle à
 * CameraX ni à Bitmap : tout passe par [IseeCameraActions] et des fonctions
 * injectées, ce qui garde l'écran testable sans appareil (voir [IseePhotoShot]).
 *
 * Un seul vol à la fois (`busy`) : le bouton est muet pendant la génération
 * (~10 s facturées ~0,01 $), et une seconde demande ne double pas la facture.
 * La chasse (« où sont mes clés ») est pilotée par la détection : l'ordre
 * arme le label, le pictogramme se génère à sa première détection SAM,
 * puis l'overlay s'ancre sur la boîte détectée : l'ancrage live est seul
 * juge tant que dure la chasse — objet hors champ (passage gabarit muet)
 * ou SAM muet, le picto tombe aussitôt et revient avec l'objet, sans
 * jamais régénérer.
 * Les clés sont jugées par SAM en permanence, chasse ou pas : SAM les
 * traque dedans comme dehors, et le gabarit seul ne les affiche jamais
 * — SAM muet (GPU froid), pas de picto clé, même si le motif matche.
 * Dehors, tout est muet (bouton, ordres, reconnaissance sur frames) : seul
 * dedans suit des pictos. Une génération lancée dedans mais livrée dehors
 * est jetée sans bruit ([dropLate]) — comme une génération effacée entre-temps.
 */
class IseePicto(
    private val media: MediaCacheStore,
    private val camera: () -> IseeCameraActions?,
    private val settings: () -> Settings,
    private val store: PictoStore,
    private val clientOf: (Settings) -> PictoImageClient,
    private val update: StateUpdate,
    private val scope: CoroutineScope,
    private val photoJpeg: (File) -> ByteArray? = ::photoToJpeg,
    private val photoTemplate: (File) -> PictoTemplate? = ::templateFromPhoto,
    private val frameGray: (String) -> GrayImage? = ::decodeFrameGray,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEDANS },
    probeState: StateFlow<LiveProbeState>? = null,
    private val onFound: (label: String, clockHour: Int) -> Unit = { _, _ -> },
) {

    /**
     * Chasse branchée à la sonde : chaque publication de détections SAM y est
     * guettée ([onDetections]) — `null` (tests) = pas d'abonnement.
     */
    init {
        probeState?.let { flow ->
            scope.launch(io) {
                flow.map { it.samDetections }.distinctUntilChanged().collect { onDetections(it) }
            }
        }
    }

    @Volatile
    private var pictos: List<StoredPicto> = emptyList()

    @Volatile
    private var busy = false

    /**
     * Génération de l'effacement : une livraison dont le numéro a vieilli
     * (effacée entre-temps) est jetée sans stocker — comme une livraison tardive.
     */
    @Volatile
    private var clearGen = 0

    @Volatile
    private var lastMatchAt = 0L

    @Volatile
    private var chipBounds: RectF? = null

    /**
     * Label chassé en direct (« keys ») : armé par [findObject], la
     * génération part à sa première détection SAM ([onDetections]), puis
     * l'overlay s'ancre sur sa boîte. `null` = pas de chasse en cours.
     */
    @Volatile
    private var huntLabel: String? = null

    /** Derniers matches locaux (gabarits), fusionnés avec [liveHits]. */
    @Volatile
    private var templateHits: List<PictoHit> = emptyList()

    /** Ancrage live (boîte SAM du label chassé), prioritaire à l'affichage. */
    @Volatile
    private var liveHits: List<PictoHit> = emptyList()

    /**
    /**
     * Cadran du dernier trouvé signalé ([onFound]), `null` si désancré :
     * le trouvé ne se re-signale qu'au changement de cadran (le répéter au
     * même cadran est le travail de l'anti-répétition, autre mission).
     */
    @Volatile
    private var lastFoundClock: Int? = null

    /**
     * SAM voit les clés sur sa dernière publication : le gabarit des clés
     * ne s'affiche que dans ce cas — SAM muet, pas de picto clé. Lu avant
     * le repli dehors, donc frais dans tous les modes.
     */
    @Volatile
    private var samKeysSeen: Boolean = false

    /** Charge les repères validés (ouverture de l'écran) et arme la détection. */
    fun refresh() {
        scope.launch(io) { load() }
    }

    /** Vrai dès qu'un repère est armé : les frames partent au matcher local. */
    fun armed(): Boolean = pictos.isNotEmpty()

    /** PNG d'un repère pour l'overlay, `null` si inconnu. */
    fun pngOf(id: String): ByteArray? = pictos.firstOrNull { it.id == id }?.png

    /**
     * Efface tous les repères (Réglages) : fichiers supprimés, mémoire vidée,
     * chasse abandonnée, overlay tombé. Une génération en vol garde son
     * `busy` : à sa livraison, son numéro a vieilli et elle est jetée
     * sans stocker — rien ne ressuscite.
     */
    fun clearAll() {
        clearGen++
        scope.launch(io) {
            runCatching { store.clear() }
            pictos = emptyList()
            templateHits = emptyList()
            liveHits = emptyList()
            huntLabel = null
            update { it.copy(picto = PictoUiState(armed = 0), status = IseeStatusText.PICTO_CLEARED) }
        }
    }

    /** Libellés armés : la chasse vocale vise l'un d'eux. */
    fun labels(): List<String> = pictos.map { it.label }

    /** Zone tactile du chip, en coordonnées fenêtre, `null` quand il n'est pas affiché. */
    fun chipZone(): RectF? = chipBounds

    /** Le chip se place / se retire : la racine exempte sa zone de l'interception. */
    fun setChipBounds(bounds: RectF?) {
        chipBounds = bounds
    }

    /**
     * Bouton « Picto » de la carte : photo de l'objet filmé, pictogramme
     * Muse, puis stockage direct — sans confirmation ni nom : le repère est
     * cherché dès la frame suivante et l'overlay le montre à son emplacement.
     * Muet dehors, pendant une génération en vol, ou si la caméra n'est pas prête.
     */
    fun onPictoButton() {
        if (!placeMode().pictoEnabled) return
        if (busy) return
        busy = true
        update { it.copy(picto = PictoUiState(phase = PictoPhase.Shooting), status = null) }
        val gen = clearGen
        launchGuarded {
            val shot = shoot()
            if (shot == null) {
                fail(IseeStatusText.PICTO_PHOTO_FAILED)
            } else {
                update { it.copy(picto = it.picto.copy(phase = PictoPhase.Generating)) }
                generateAndArm(shot.first, shot.second, "", gen)
            }
        }
    }

    /**
     * Ordre « Find the X » / « Where are my keys » / « où sont mes clés » :
     * arme la chasse de [label] — le pictogramme se génère à sa première
     * détection sur l'image ([onDetections]), pas sur une photo à
     * l'aveugle, puis s'ancre sur les détections. Déjà armé : la chasse
     * reprend sans régénérer, une seconde demande ne double pas la
     * facture. Muet dehors ou si une génération est en vol.
     */
    fun findObject(label: String) {
        if (!placeMode().pictoEnabled) return
        if (busy || label.isBlank()) return
        huntLabel = label.trim()
        liveHits = emptyList()
        lastFoundClock = null
        update { it.copy(picto = it.picto.copy(hits = mergedHits(), armed = pictos.size), status = null) }
    }

    /**
     * « find off » : la chasse pendante est effacée — label, ancrage live
     * et cadran du trouvé. L'affichage retombe sur les gabarits armés
     * (repères conservés : une nouvelle chasse reprend sans régénérer).
     * Idempotent : sans chasse, ne coûte rien. Le statut est au demandeur.
     */
    fun cancelHunt() {
        huntLabel = null
        liveHits = emptyList()
        lastFoundClock = null
        publishHits()
    }

    /** Label chassé en direct, `null` = pas de chasse en cours. */
    fun hunting(): String? = huntLabel

    /**
     * Frame filmée (JPEG base64) : les repères armés y sont cherchés au plus
     * toutes les [MATCH_EVERY_MS] — 2 Hz, l'overlay garde le dernier passage.
     * Chasse : un passage muet sur le picto chassé périme son ancrage live.
     */
    fun onFrame(jpegB64: String) {
        if (!placeMode().pictoEnabled) return
        val known = pictos
        if (known.isEmpty()) return
        val now = clock()
        if (now - lastMatchAt < MATCH_EVERY_MS) return
        lastMatchAt = now
        scope.launch(io) {
            val frame = frameGray(jpegB64) ?: return@launch
            val hits = known.mapNotNull { matchTemplate(frame, it.template, it.id, it.label) }
            templateHits = hits
            // Chasse : la frame fraîche ne voit plus l'objet — l'ancrage live
            // précédent est périmé, le picto tombe sans attendre SAM (qui le
            // ré-ancre à sa prochaine publication si l'objet est toujours là).
            huntLabel?.let { hunt ->
                armedFor(hunt)?.let { armed ->
                    if (hits.none { it.pictoId == armed.id }) {
                        liveHits = liveHits.filter { it.pictoId != armed.id }
                    }
                }
            }
            publishHits()
        }
    }

    /**
     * Détections SAM confirmées de la dernière frame : le label chassé
     * ([findObject]) y est guetté. Première détection sans repère armé :
     * photo des clés cadrées, puis génération et armement direct, sans
     * confirmation — l'utilisateur est seul devant la caméra. Repère armé :
     * l'overlay s'ancre sur la boîte détectée ; les clés sorties du
     * champ, le picto disparaît, et il réapparaît avec elles, sans
     * régénérer. Muet dehors ; sans chasse, la publication ne fait que juger
     * les clés. Chaque ancrage frais (apparition ou changement de cadran)
     * signale le trouvé ([onFound]) : le câblage le poste en clip, au cran find.
     */
    fun onDetections(dets: List<Detection>) {
        samKeysSeen = dets.any { matchesHunt(KEYS_LABEL, it.label) }
        if (!placeMode().pictoEnabled) return
        val hunt = huntLabel
        if (hunt == null) {
            publishHits()
            return
        }
        val armed = armedFor(hunt)
        val seen = dets.firstOrNull { matchesHunt(hunt, it.label) }
        if (seen != null && armed == null) {
            if (busy) return
            busy = true
            update { it.copy(picto = PictoUiState(phase = PictoPhase.Shooting, armed = pictos.size), status = null) }
            val gen = clearGen
            launchGuarded {
                val shot = shoot()
                if (shot == null) {
                    fail(IseeStatusText.PICTO_PHOTO_FAILED)
                } else {
                    update { it.copy(picto = it.picto.copy(phase = PictoPhase.Generating)) }
                    generateAndArm(shot.first, shot.second, hunt, gen)
                }
            }
            return
        }
        if (armed == null) return
        val box = seen?.normalizedBox()
        if (seen == null || box == null || box.size < 4) {
            liveHits = emptyList()
            lastFoundClock = null
            publishHits()
            return
        }
        val clock = PositionText.clockHour((box[0] + box[2]) / 2f)
        val fresh = liveHits.none { it.pictoId == armed.id } || lastFoundClock != clock
        liveHits = listOf(PictoHit(armed.id, armed.label, box[0], box[1], box[2] - box[0], box[3] - box[1], seen.score))
        publishHits()
        if (fresh) {
            lastFoundClock = clock
            onFound(hunt, clock)
        }
    }

    /** Relit le magasin et publie le nombre de repères armés. */
    private fun load() {
        pictos = store.list()
        val count = pictos.size
        update { it.copy(picto = it.picto.copy(armed = count)) }
    }

    /** Photo de l'objet filmé : octets d'envoi + gabarit, `null` si ratée. */
    private suspend fun shoot(): Pair<ByteArray, PictoTemplate>? {
        val cam = camera() ?: return null
        val target = media.newPhotoFile()
        val done = CompletableDeferred<Boolean>()
        if (!cam.takePhoto(target) { saved -> done.complete(saved) }) return null
        val ok = withTimeoutOrNull(IseePhotoShot.CAPTURE_TIMEOUT_MS) { done.await(); true } ?: false
        if (!ok || !target.isFile || target.length() <= 0L) return null
        val jpeg = photoJpeg(target) ?: return null
        val template = photoTemplate(target) ?: return null
        return Pair(jpeg, template)
    }

    /** Appel Muse puis stockage direct : l'overlay parle, pas d'avant-plan. */
    private suspend fun generateAndArm(photo: ByteArray, template: PictoTemplate, label: String, gen: Int) {
        val result = clientOf(settings()).editToPictogram(photo, label)
        result.fold(
            onSuccess = { png ->
                if (gen != clearGen) return@fold dropLate()
                if (!placeMode().pictoEnabled) return@fold dropLate()
                runCatching { store.save(label, png, template) }.onFailure {
                    fail(IseeStatusText.pictoFailed(it))
                    return
                }
                load()
                busy = false
                update { it.copy(picto = PictoUiState(armed = pictos.size), status = IseeStatusText.PICTO_SAVED) }
            },
            onFailure = { fail(IseeStatusText.pictoFailed(it)) },
        )
    }

    /** Tâche gardée : une levée inattendue (caméra…) rend un motif, jamais un blocage. */
    private fun launchGuarded(block: suspend () -> Unit) {
        scope.launch(io) {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                fail(IseeStatusText.pictoFailed(e))
            }
        }
    }

    /**
     * Livraison tardive (génération lancée dedans puis bascule dehors, ou repères
     * effacés entre-temps) :
     * jetée sans bruit — ni stockage, ni statut (la confirmation de bascule
     * reste à l'écran). Les repères déjà armés le restent.
     */
    private fun dropLate() {
        busy = false
        update { it.copy(picto = PictoUiState(armed = pictos.size)) }
    }

    /** Repère armé visant [label], `null` = rien à ancrer, tout reste à générer. */
    private fun armedFor(label: String): StoredPicto? =
        pictos.firstOrNull { matchesHunt(label, it.label) }

    /**
     * Le label détecté vise-t-il la chasse, dans un sens ou dans l'autre
     * (« keys » ↔ « keys », « remote » ↔ « tv remote ») ? Égalité
     * canonique ou règle vocale (singulier/pluriel, abrégé oral).
     */
    private fun matchesHunt(hunt: String, label: String): Boolean =
        KnownSizes.canonical(hunt) == KnownSizes.canonical(label) ||
            PictoVoiceOrder.matches(label, hunt) || PictoVoiceOrder.matches(hunt, label)

    /** Les deux sources fusionnées (voir [mergedHits]), publiées à l'écran. */
    private fun publishHits() {
        update { it.copy(picto = it.picto.copy(hits = mergedHits())) }
    }

    /**
     * Overlay affichable : l'ancrage live d'abord, puis les gabarits ni
     * ancrés ni chassés — et les clés seulement quand SAM les voit,
     * chasse ou pas : le gabarit seul ne les montre jamais, même si le
     * motif matche.
     */
    private fun mergedHits(): List<PictoHit> {
        val live = liveHits
        // Chasse : l'ancrage live est seul juge pour le picto chassé — le
        // passage gabarit précédent ne le ressuscite pas quand SAM dit parti.
        val huntedId = huntLabel?.let { armedFor(it)?.id }
        val keysId = armedFor(KEYS_LABEL)?.id
        val templates = templateHits.filter { hit ->
            live.none { it.pictoId == hit.pictoId } && hit.pictoId != huntedId &&
                (hit.pictoId != keysId || samKeysSeen)
        }
        return live + templates
    }

    /** Échec : repos, motif affiché. */
    private fun fail(status: String) {
        busy = false
        update { it.copy(picto = PictoUiState(armed = pictos.size), status = status) }
    }

    companion object {
        /** Cadence max de la reconnaissance : 2 Hz, sur le thread d'I/O. */
        const val MATCH_EVERY_MS = 500L

        /**
         * Label jugé par SAM : les clés sont un concept SAM suivi dedans
         * comme dehors, le seul repère dont l'affichage exige SAM.
         */
        const val KEYS_LABEL = "keys"
    }
}
