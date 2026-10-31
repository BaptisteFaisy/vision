package dev.sam3vision.app.isee

import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.app.isee.vm.IseeCameraCoordinator
import dev.sam3vision.app.isee.vm.IseeDictation
import dev.sam3vision.app.isee.vm.IseeLiveDictation
import dev.sam3vision.app.isee.vm.IseePhotoShot
import dev.sam3vision.app.isee.vm.IseeStreamDictation
import dev.sam3vision.core.glasses.CaptureAction
import dev.sam3vision.core.settings.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Gestes « parler à iSee » sur le téléphone : appui long (question), tap
 * (vidéo). Extrait d'`IseeViewModel` (garde 500 lignes) : pur relais vers les
 * pièces existantes, aucun comportement changé.
 */
internal class IseeHoldTalk(
    private val scope: CoroutineScope,
    private val isActive: () -> Boolean,
    private val silence: () -> Unit,
    private val shots: IseePhotoShot,
    private val snapshot: () -> IseeUiState,
    private val update: ((IseeUiState) -> IseeUiState) -> Unit,
    private val haptics: IseeHaptics,
    private val dictation: IseeDictation,
    private val liveDictation: IseeLiveDictation,
    private val streamDictation: IseeStreamDictation,
    private val settings: () -> Settings,
    private val cameraOps: IseeCameraCoordinator,
    private val finishHold: suspend () -> Unit,
    /** Tap avec photo lunettes affichée : elle part, la caméra repart (`false` = rien). */
    private val onTapPhoto: () -> Boolean,
) {
    /** Appui long : la frame courante devient le contexte (sauf photo gelée), puis le micro s'ouvre. */
    fun start() {
        if (!isActive()) return
        // Parler coupe lecture et guidage (sinon la tenue est jetée, le micro reste pris).
        silence()
        // Ce qui est filmé devient le contexte de la question : une frame d'une
        // tenue précédente est périmée et gagnerait sinon la place (question
        // portant sur une image figée).
        if (snapshot().recordingVideo) shots.releaseContext()
        shots.captureForHold()
        update { it.copy(holding = true, status = null) }
        haptics.holdStart()
        if (dictation.start()) {
            // Stream temps réel d'abord ; pas de clé / drapeau coupé / mort
            // en route ⇒ les aperçus batch reprennent (même moteur final).
            // Une mort après le relâchement ne relance rien : le moteur final
            // a déjà choisi, un aperçu fantôme tournerait pour personne.
            val attempting = streamDictation.start(settings()) {
                if (snapshot().holding) liveDictation.start(settings())
            }
            if (!attempting) liveDictation.start(settings())
        }
    }

    /** Doigt relâché : micro coupé, transcription, MP3 en cache, réponse IA parlée. */
    fun end() {
        if (!isActive()) return
        haptics.holdEnd()
        if (!snapshot().holding) return
        liveDictation.stop()
        update { it.copy(holding = false) }
        scope.launch { finishHold() }
    }

    /** Tenue annulée (app en arrière-plan ou geste perdu) : micro relâché, rien de transcrit. */
    fun cancel() {
        if (!snapshot().holding) return
        liveDictation.stop()
        streamDictation.stop()
        dictation.discard()
        haptics.holdEnd()
        update { it.copy(holding = false) }
    }

    /** Tap : photo lunettes affichée → elle part et la caméra repart ; sinon la vidéo bascule. */
    fun singleTap() {
        if (!isActive()) return
        if (onTapPhoto()) return
        if (snapshot().recordingVideo) cameraOps.stopVideo() else cameraOps.startVideo()
    }
}

/**
 * Boîte aux lettres des lunettes : photos posées sur l'écran iSee, questions
 * entendues au micro des lunettes. Extraite d'`IseeViewModel` (garde 500
 * lignes) : pur relais, aucun comportement changé.
 */
internal class IseeGlassesInbox(
    private val scope: CoroutineScope,
    private val shots: IseePhotoShot,
    private val snapshot: () -> IseeUiState,
    private val update: ((IseeUiState) -> IseeUiState) -> Unit,
    private val silence: () -> Unit,
    private val answer: suspend (text: String, pcm: ByteArray) -> Unit,
    private val stopVideo: () -> Unit,
    private val stopProbe: () -> Unit,
    private val restartCamera: () -> Unit,
) {
    /** Compteur des photos lunettes affichées : deux photos n'ont jamais le même `seq`. */
    private var seq = 0L

    /**
     * Photo prise avec les lunettes : elle s'affiche sur cet écran (personne
     * accompagnée), sans quitter la page courante — posée même depuis un autre
     * écran, elle attend le retour sur iSee. Doublon (même fichier) ignoré,
     * photo suivante remplace la précédente ([acceptGlassesPhoto]), épinglée
     * en contexte, caméra coupée.
     */
    fun onPhoto(file: File, action: CaptureAction) {
        if (!acceptGlassesPhoto(snapshot().glassesPhoto, file)) return
        seq += 1
        update { it.copy(glassesPhoto = IseeGlassesPhoto(file, action, seq), status = glassesPhotoLabel(action)) }
        shots.setPinnedContext(file)
        if (snapshot().recordingVideo) stopVideo()
        stopProbe()
    }

    /** La photo part (tap, suivante), son contexte avec elle ; `false` = aucune. */
    fun dismiss(): Boolean {
        if (snapshot().glassesPhoto == null) return false
        update { it.copy(glassesPhoto = null) }
        shots.clearPinnedContext()
        shots.releaseContext()
        return true
    }

    /** Tap avec photo affichée : elle part et la caméra repart. Sinon rien. */
    fun onTap(): Boolean {
        if (!dismiss()) return false
        restartCamera()
        return true
    }

    /**
     * Question entendue aux lunettes (écoute seule, sans photo) : même
     * traitement qu'une dictée tenue-relâchée. Le téléphone reste en poche.
     */
    fun heard(text: String, pcm: ByteArray) {
        silence()
        scope.launch { answer(text, pcm) }
    }
}
