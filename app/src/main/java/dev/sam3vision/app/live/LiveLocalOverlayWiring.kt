package dev.sam3vision.app.live

import android.graphics.Bitmap
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.LiveLocalOverlaySession
import dev.sam3vision.app.LiveUiState
import dev.sam3vision.app.keyframeConfidence
import dev.sam3vision.app.localOverlayFlag
import dev.sam3vision.app.localReasonNote
import dev.sam3vision.core.local.LocalReason
import dev.sam3vision.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Câblage du calque local dans l'état du direct (lane L13, agent D/u13) : le moteur, le
 * rendu, la persistance du réglage et la publication de l'état vivent ici, pour que le
 * ViewModel n'en garde que le branchement (la limite des 500 lignes du ViewModel est
 * atteinte à une ligne près : cette unité n'y tient pas).
 *
 * Le **keyframe** est repéré sans que le ViewModel ait à le signaler : la liste
 * `overlays` de l'état change à chaque frame publiée par le serveur, et la confiance
 * vient de la frame publiée. Un tick qui voit une nouvelle liste pose le keyframe avant
 * de décider — l'ordre du contrat de fusion (§2.2) est donc respecté.
 *
 * Le drapeau effectif est recalculé à chaque tick : la bascule du bandeau prime, sinon
 * le réglage lu au démarrage — le tout passé au crible du drapeau de build, qui reste
 * prioritaire. Le moteur n'est recâblé que quand ce drapeau bouge.
 */
class LiveLocalOverlayWiring(
    private val state: MutableStateFlow<LiveUiState>,
    private val store: SettingsStore,
    private val settingEnabled: () -> Boolean,
) {

    private val session = LiveLocalOverlaySession()

    /** Dernière liste d'overlays vue : son identité annonce un nouveau keyframe. */
    private var seenOverlays: List<Bitmap>? = null

    /** Instant du dernier état publié : les barres ne se recomposent pas à 30 ips. */
    private var stateAtMs = 0L

    /** Valeur posée par la bascule : elle prime sur les réglages lus au démarrage. */
    private var manual: Boolean? = null

    /** Image du calque (30 ips) : son propre flux, **hors** de l'état du direct. */
    val frame: StateFlow<Bitmap?> = session.frame

    /** Bascule du bandeau : le réglage est persisté, puis appliqué sans relancer le direct. */
    suspend fun toggle() {
        val next = store.load().let { it.copy(localOverlayEnabled = !it.localOverlayEnabled) }
        store.save(next)
        manual = next.localOverlayEnabled
    }

    /** Plan de luminance d'une frame caméra (256×103) : l'entrée de l'estimateur. */
    fun lumaFor(bitmap: Bitmap): ByteArray? = session.lumaFor(bitmap)

    /**
     * Tick d'une frame caméra, **avant** toute décision réseau (contrat de fusion §2.1).
     * Une liste d'overlays neuve vaut keyframe : ses bitmaps coloriés et la luminance
     * courante (copiée, le plan est réutilisé d'une frame à l'autre) deviennent la
     * référence du calque.
     */
    fun tick(luma: ByteArray?, nowMs: Long) {
        syncEnabled()
        val overlays = state.value.overlays
        if (overlays !== seenOverlays) {
            seenOverlays = overlays
            val frame = state.value.frame
            session.onKeyframe(overlays, frame?.let { keyframeConfidence(it) } ?: 0f, luma?.copyOf(), nowMs)
        }
        val tick = session.tick(nowMs, luma)
        publishState(tick.fps, tick.reason, nowMs)
    }

    /** Fin de session : plus de keyframe, plus d'image peinte (l'état est remis à zéro). */
    fun reset() {
        session.reset()
        seenOverlays = null
    }

    /**
     * Drapeau effectif : la bascule du bandeau prime, sinon le réglage lu au démarrage —
     * le tout passé au crible du drapeau de build. Le moteur n'est recâblé que quand il
     * bouge, et la session repart alors de zéro (keyframe, image, cadence).
     */
    private fun syncEnabled() {
        val enabled = localOverlayFlag(BuildConfig.LOCAL_OVERLAY, manual ?: settingEnabled())
        if (enabled == state.value.localEnabled && enabled == session.enabled) return
        session.reset()
        session.setEnabled(enabled)
        state.update {
            it.copy(localEnabled = enabled, localActive = false, localFps = 0f, localNote = "")
        }
    }

    /** Libère les tampons de peinture (fin du ViewModel). */
    fun release() = session.release()

    /** État du calque publié dans les barres : au plus une fois par [STATE_MS]. */
    private fun publishState(fps: Float, reason: LocalReason, nowMs: Long) {
        val active = session.frame.value != null
        if (active == state.value.localActive && nowMs - stateAtMs < STATE_MS) return
        stateAtMs = nowMs
        state.update {
            it.copy(localActive = active, localFps = fps, localNote = localReasonNote(reason))
        }
    }

    private companion object {
        /** Deux publications par seconde suffisent pour la pastille et la ligne d'état. */
        const val STATE_MS = 500L
    }
}