package dev.sam3vision.app.glasses

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Une frame du flux des lunettes, copiée à la collecte : le SDK peut réutiliser ses buffers,
 * donc l'UI ne voit jamais un `VideoFrame` vivant — que ces octets-ci, purs et testables.
 */
data class GlassesFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val compressed: Boolean,
    val codecConfig: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassesFrame) return false
        return width == other.width && height == other.height &&
            presentationTimeUs == other.presentationTimeUs &&
            compressed == other.compressed && codecConfig == other.codecConfig &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + compressed.hashCode()
        result = 31 * result + codecConfig.hashCode()
        return result
    }
}

/** Taille de la vidéo décodée, pour cadrer la vue (recadrage pur, testé en JVM). */
data class VideoSize(val width: Int, val height: Int)

/** Journal du direct (recette : `logcat -s GlassesVideo`). */
private const val TAG = "GlassesVideo"

/**
 * Une poussée de frame, numérotée : deux poussées identiques (le mock rejoue la même image)
 * émettent quand même deux fois, sinon le collecteur ne réessaierait jamais son rendu.
 */
data class GlassesFrameBox(val seq: Long, val frame: GlassesFrame)

/**
 * Passerelle entre le module lunettes et l'accueil iSee, qui ne se connaissent pas :
 * `GlassesCamera` y pousse les frames du flux DAT, l'accueil les rend sur sa surface.
 *
 * Même motif que [GlassesMicLink] : un seul point d'entrée, sans référence au SDK ni à
 * l'UI. `live` ne passe à vrai qu'à la première frame **rendue** (jamais de rue noire :
 * l'accueil n'ouvre la zone vidéo que sur du décodable avéré).
 */
object GlassesVideoLink {

    private val _live = MutableStateFlow(false)
    private val _streaming = MutableStateFlow(false)
    private val _frames = MutableStateFlow<GlassesFrameBox?>(null)
    private var seq = 0L
    private val _videoSize = MutableStateFlow<VideoSize?>(null)

    /** Vrai dès la première frame rendue, jusqu'à l'arrêt du flux. */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    /**
     * Vrai tant que le flux des lunettes tourne, même avant la première image. L'accueil
     * s'en sert pour préparer sa surface (fantôme invisible) : sans elle, aucune frame ne
     * pourrait se rendre et `live` ne passerait jamais à vrai.
     */
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    /** Dernière poussée (conflaté : l'UI prend toujours la plus récente, jamais deux fois la même). */
    val frames: StateFlow<GlassesFrameBox?> = _frames.asStateFlow()

    @Volatile
    private var snapshot: (() -> android.graphics.Bitmap?)? = null

    /** La rue fournit ses pixels pour la sonde SAM (photo de ce qui s'affiche). */
    fun attachSnapshot(provider: () -> android.graphics.Bitmap?) {
        snapshot = provider
    }

    /** La rue ne regarde plus : plus de photo pour la sonde. */
    fun detachSnapshot() {
        snapshot = null
    }

    /** Photographie la rue des lunettes, ou `null` si elle ne regarde pas. */
    fun takeSnapshot(): android.graphics.Bitmap? = runCatching { snapshot?.invoke() }.getOrNull()

    @Volatile
    private var fallbackCsdField: ByteArray? = null

    /**
     * Paramètres H.265 hors bande (VPS/SPS/PPS) : le flux simulé n'envoie que des tranches,
     * le décodeur les prend ici faute de config dans le flux. Toujours `null` sur des
     * lunettes réelles (la config voyage dans le flux).
     */
    var fallbackCsd: ByteArray?
        get() = fallbackCsdField
        set(value) {
            fallbackCsdField = value
        }

    /** Taille décodée, pour le recadrage de la vue (`null` = inconnue). */
    val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()

    @Volatile
    private var notes: ((GlassesMessage) -> Unit)? = null

    /** Le contrôleur reçoit les notes du décodeur (affichées dans Réglages → Lunettes). */
    fun attachNotes(sink: (GlassesMessage) -> Unit) {
        notes = sink
    }

    /** Session terminée : plus de notes du décodeur pour personne. */
    fun detachNotes() {
        notes = null
    }

    /** Note du décodeur vers l'utilisateur (muette sans puits, journalisée côté décodeur). */
    fun note(message: GlassesMessage) {
        runCatching { notes?.invoke(message) }
    }

    /** Copie reçue du flux : écrase la précédente, sans suspendre, en émettant toujours. */
    fun push(frame: GlassesFrame) {
        seq += 1
        _frames.value = GlassesFrameBox(seq, frame)
    }

    /** Une frame vient d'être rendue : le direct est prouvé vivant. */
    fun markRendered() {
        if (!_live.value) Log.i(TAG, "direct lunettes : première image rendue, live=true")
        _live.value = true
    }

    /**
     * Le flux démarre ou s'arrête. Le démarrage n'allume que `streaming` (la surface se
     * prépare) : `live` ne vient qu'à la première image rendue. L'arrêt oublie tout.
     */
    fun setStreaming(streaming: Boolean) {
        _streaming.value = streaming
        if (!streaming) {
            _live.value = false
            _frames.value = null
            _videoSize.value = null
        }
    }

    /** Session terminée : oubli complet, comme si rien n'avait streamé. */
    fun clear() {
        setStreaming(false)
        // Ici, pas dans setStreaming(false) : le flux émet `false` en ouverture de
        // session, pile entre la pose du repli (mock) et sa lecture (décodeur).
        fallbackCsdField = null
    }

    /** Taille annoncée par le décodeur (un changement de résolution recadre la vue). */
    fun onVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) _videoSize.value = VideoSize(width, height)
    }
}
