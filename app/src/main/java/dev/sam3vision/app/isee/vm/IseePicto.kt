package dev.sam3vision.app.isee.vm

import android.graphics.RectF
import android.os.SystemClock
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.isee.StoredPicto
import dev.sam3vision.app.isee.decodeFrameGray
import dev.sam3vision.app.isee.photoToJpeg
import dev.sam3vision.app.isee.templateFromPhoto
import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.picto.matchTemplate
import dev.sam3vision.core.settings.Settings
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Où en est le repère : repos, photo, génération réseau, ou relecture aidant. */
enum class PictoPhase { Idle, Shooting, Generating, Review }

/**
 * L'état picto de l'écran : la phase, le brouillon relu, les objets reconnus
 * sur la vidéo (overlay temps réel) et le nombre de repères armés.
 */
data class PictoUiState(
    val phase: PictoPhase = PictoPhase.Idle,
    val draftPng: ByteArray? = null,
    val draftLabel: String = "",
    val hits: List<PictoHit> = emptyList(),
    val armed: Int = 0,
)

/**
 * Les repères visuels : photo de l'objet filmé → pictogramme Muse → relecture
 * aidant → reconnaissance temps réel sur la vidéo. Rien ici ne parle à CameraX
 * ni à Bitmap : tout passe par [IseeCameraActions] et des fonctions injectées,
 * ce qui garde l'écran testable sans appareil (voir [IseePhotoShot]).
 *
 * Un seul vol à la fois (`busy`) : le bouton est muet pendant la génération
 * (~10 s facturées ~0,01 $), et une seconde demande ne double pas la facture.
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
) {

    /** Brouillon en relecture : photo d'origine (régénération), gabarit, PNG. */
    private class Draft(val photo: ByteArray, val template: PictoTemplate, var png: ByteArray)

    @Volatile
    private var pictos: List<StoredPicto> = emptyList()

    @Volatile
    private var draft: Draft? = null

    @Volatile
    private var busy = false

    @Volatile
    private var lastMatchAt = 0L

    @Volatile
    private var chipBounds: RectF? = null

    /** Charge les repères validés (ouverture de l'écran) et arme la détection. */
    fun refresh() {
        scope.launch(io) { load() }
    }

    /** Vrai dès qu'un repère est armé : les frames partent au matcher local. */
    fun armed(): Boolean = pictos.isNotEmpty()

    /** PNG d'un repère pour l'overlay, `null` si inconnu. */
    fun pngOf(id: String): ByteArray? = pictos.firstOrNull { it.id == id }?.png

    /** Libellés armés : la chasse vocale vise l'un d'eux. */
    fun labels(): List<String> = pictos.map { it.label }

    /** Zone tactile du chip, en coordonnées fenêtre, `null` quand il n'est pas affiché. */
    fun chipZone(): RectF? = chipBounds

    /** Le chip se place / se retire : la racine exempte sa zone de l'interception. */
    fun setChipBounds(bounds: RectF?) {
        chipBounds = bounds
    }

    /**
     * Bouton « Picto » de la carte : photo de l'objet filmé, puis pictogramme.
     * Muet si une génération est en vol, ou si la caméra n'est pas prête.
     */
    fun onPictoButton() {
        if (busy) return
        busy = true
        update { it.copy(picto = PictoUiState(phase = PictoPhase.Shooting), status = null) }
        scope.launch(io) {
            val shot = shoot()
            if (shot == null) {
                fail(IseeStatusText.PICTO_PHOTO_FAILED)
                return@launch
            }
            update { it.copy(picto = it.picto.copy(phase = PictoPhase.Generating)) }
            generate(shot.first, shot.second, "")
        }
    }

    /** L'aidant valide : le repère est stocké et la détection l'arme aussitôt. */
    fun confirmDraft(label: String) {
        val pending = draft ?: return
        draft = null
        scope.launch(io) {
            runCatching { store.save(label, pending.png, pending.template) }
            load()
            update { it.copy(picto = PictoUiState(armed = pictos.size), status = IseeStatusText.PICTO_SAVED) }
        }
    }

    /** L'aidant n'aime pas le dessin : régénération avec son nom comme indice. */
    fun regenerateDraft(label: String) {
        val pending = draft ?: return
        if (busy) return
        busy = true
        update { it.copy(picto = it.picto.copy(phase = PictoPhase.Generating)) }
        scope.launch(io) { generate(pending.photo, pending.template, label) }
    }

    /** L'aidant abandonne : rien n'est stocké, rien ne reste armé de plus. */
    fun dismissDraft() {
        draft = null
        busy = false
        update { it.copy(picto = it.picto.copy(phase = PictoPhase.Idle, draftPng = null)) }
    }

    /**
     * Frame filmée (JPEG base64) : les repères armés y sont cherchés au plus
     * toutes les [MATCH_EVERY_MS] — 2 Hz, l'overlay garde le dernier passage.
     */
    fun onFrame(jpegB64: String) {
        val known = pictos
        if (known.isEmpty()) return
        val now = clock()
        if (now - lastMatchAt < MATCH_EVERY_MS) return
        lastMatchAt = now
        scope.launch(io) {
            val frame = frameGray(jpegB64) ?: return@launch
            val hits = known.mapNotNull { matchTemplate(frame, it.template, it.id, it.label) }
            update { it.copy(picto = it.picto.copy(hits = hits)) }
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

    /** Appel Muse : le PNG part en relecture, l'échec en ligne de statut. */
    private suspend fun generate(photo: ByteArray, template: PictoTemplate, hint: String) {
        val result = clientOf(settings()).editToPictogram(photo, hint)
        result.fold(
            onSuccess = { png ->
                draft = Draft(photo, template, png)
                busy = false
                update {
                    it.copy(
                        picto = it.picto.copy(phase = PictoPhase.Review, draftPng = png, draftLabel = hint),
                        status = null,
                    )
                }
            },
            onFailure = { fail(IseeStatusText.pictoFailed(it)) },
        )
    }

    /** Échec : repos, brouillon jeté, motif affiché. */
    private fun fail(status: String) {
        draft = null
        busy = false
        update { it.copy(picto = PictoUiState(armed = pictos.size), status = status) }
    }

    companion object {
        /** Cadence max de la reconnaissance : 2 Hz, sur le thread d'I/O. */
        const val MATCH_EVERY_MS = 500L
    }
}
