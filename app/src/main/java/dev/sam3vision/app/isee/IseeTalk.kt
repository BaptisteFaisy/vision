package dev.sam3vision.app.isee

import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.app.isee.vm.IseeCameraCoordinator
import dev.sam3vision.app.isee.vm.IseeDictation
import dev.sam3vision.app.isee.vm.IseeLiveDictation
import dev.sam3vision.app.isee.vm.IseePhotoShot
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
    private val settings: () -> Settings,
    private val cameraOps: IseeCameraCoordinator,
    private val finishHold: suspend () -> Unit,
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
        if (dictation.start()) liveDictation.start(settings())
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
        dictation.discard()
        haptics.holdEnd()
        update { it.copy(holding = false) }
    }

    /** Tap simple n'importe où : démarre la vidéo (fenêtre flottante), ou la ferme. */
    fun singleTap() {
        if (!isActive()) return
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
) {
    /** Compteur des photos lunettes affichées : deux photos n'ont jamais le même `seq`. */
    private var seq = 0L

    /**
     * Photo prise avec les lunettes : elle s'affiche sur cet écran (personne
     * accompagnée), sans quitter la page courante — posée même depuis un autre
     * écran, elle attend le retour sur iSee. Doublon (même fichier) ignoré,
     * photo suivante remplace la précédente ([acceptGlassesPhoto]). ASK :
     * la photo devient aussi le contexte de la prochaine question dictée.
     */
    fun onPhoto(file: File, action: CaptureAction) {
        if (!acceptGlassesPhoto(snapshot().glassesPhoto, file)) return
        seq += 1
        update { it.copy(glassesPhoto = IseeGlassesPhoto(file, action, seq), status = glassesPhotoLabel(action)) }
        if (action == CaptureAction.ASK) shots.setExternalContext(file)
    }

    /** La photo des lunettes est fermée (retour, bouton) : l'écran iSee reste. */
    fun dismiss() {
        val dismissed = snapshot().glassesPhoto
        update { it.copy(glassesPhoto = null) }
        // Question annulée avec sa photo : le contexte ne doit pas fuir vers
        // la question suivante (déjà consommé, le relâchement est neutre).
        if (dismissed?.action == CaptureAction.ASK) shots.releaseContext()
    }

    /**
     * Question entendue aux lunettes : photo-contexte affichée sur cet écran
     * (comme [onPhoto] en ASK), puis même traitement qu'une dictée
     * tenue-relâchée. Le téléphone reste en poche.
     */
    fun heard(photo: File?, text: String, pcm: ByteArray) {
        silence()
        if (photo != null) onPhoto(photo, CaptureAction.ASK)
        scope.launch { answer(text, pcm) }
    }
}
