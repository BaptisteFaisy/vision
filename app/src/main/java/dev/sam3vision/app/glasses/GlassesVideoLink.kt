package dev.sam3vision.app.glasses

import android.os.SystemClock
import android.util.Log
import dev.sam3vision.core.live.GlassesClockOffset
import dev.sam3vision.core.live.GlassesLinkStats
import dev.sam3vision.core.live.LinkDrop
import dev.sam3vision.core.live.TimingsText
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
    /**
     * Instant d'arrivée côté téléphone (`SystemClock.elapsedRealtimeNanos()`), posé par
     * la pompe à la collecte ; `0` = non daté (banc, mock). Hors `equals`/`hashCode` :
     * c'est une métadonnée de mesure, pas l'identité de l'image (la conflation compare
     * les pixels, pas l'heure).
     */
    val arrivedAtNs: Long = 0L,
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

/** Journal de la mesure du lien (recette B1/B2 : `logcat -s GlassesLink`). */
private const val LINK_TAG = "GlassesLink"

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
    private val _renderedSeq = MutableStateFlow<Long?>(null)
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
        synchronized(timingLock) { noteArrivalLocked(frame, seq) }
    }

    /**
     * Numéro de la dernière frame rendue (`null` = aucune) : apparié au `seq` de la
     * poussée, il borne la latence collecte → rendu. Publié par [markRendered].
     */
    val renderedSeq: StateFlow<Long?> = _renderedSeq.asStateFlow()

    /**
     * Une frame vient d'être rendue : le direct est prouvé vivant. [seq] = le numéro
     * de la poussée rendue (`-1` = inconnu, `live` seul est allumé).
     */
    fun markRendered(seq: Long = -1L) {
        if (!_live.value) Log.i(TAG, "direct lunettes : première image rendue, live=true")
        _live.value = true
        if (seq >= 0L) _renderedSeq.value = seq
    }

    // ---- Mesure du lien (B1/B2) -------------------------------------------------
    // Trois threads s'y croisent (pompe SDK → push, rendu → noteRendered, UI →
    // markPixel) : tout ce bloc vit sous [timingLock]. Horloge unique
    // `elapsedRealtimeNanos`, la même que `arrivedAtNs` (jamais `nanoTime` ni
    // `currentTimeMillis` : deux bases différentes fausseraient les étages).

    /** Rendu daté en attente de son pixel : le chaînon entre décodeur et affichage. */
    private data class PendingLink(
        val seq: Long,
        val linkUs: Long,
        val decodeUs: Long,
        val decodeEndNs: Long,
    )

    private val timingLock = Any()

    /** Nanosecondes par microseconde (tous les étages du lien sont en µs). */
    private const val NS_PER_US = 1_000L

    /** Cadence du résumé [LINK_TAG] : 1 Hz au plus, comme la recette l'exige. */
    private const val SUMMARY_NS = 1_000_000_000L
    private val clockOffset = GlassesClockOffset()
    private val linkStats = GlassesLinkStats()
    private var awaitingNote = false
    private var lastLinkSeq = -1L
    private var lastLinkUs = -1L
    private var pending: PendingLink? = null
    private var lastSummaryNs = 0L

    /**
     * Arrivée d'une poussée : alimente le décalage pts ↔ téléphone (B1) et compte
     * les abandons d'amont. Une poussée décodable qui en écrase une autre jamais
     * rendue est une conflation, pas une mesure manquante.
     */
    private fun noteArrivalLocked(frame: GlassesFrame, pushedSeq: Long) {
        if (frame.codecConfig) {
            linkStats.drop(LinkDrop.CODEC_CONFIG)
            return
        }
        if (frame.width <= 0 || frame.height <= 0 || frame.bytes.isEmpty()) {
            linkStats.drop(LinkDrop.DIMENSIONS)
            return
        }
        if (awaitingNote) linkStats.drop(LinkDrop.CONFLATED)
        awaitingNote = true
        lastLinkSeq = pushedSeq
        lastLinkUs = observeLinkUs(frame)
    }

    /** Retard variable radio (µs) d'une frame datée, `-1` si elle ne l'est pas (mock, banc). */
    private fun observeLinkUs(frame: GlassesFrame): Long {
        if (frame.arrivedAtNs <= 0L) return -1L
        val jitterNs = clockOffset.observe(frame.arrivedAtNs, frame.presentationTimeUs) ?: return -1L
        return (jitterNs + NS_PER_US / 2L) / NS_PER_US
    }

    /**
     * Une frame vient d'être rendue, bornes du rendu à l'appui ([decodeStartNs],
     * [decodeEndNs] monotones) : allume le direct comme [markRendered], puis arme
     * le pixel. Un rendu précédent jamais affiché est conflé (l'afficheur ne
     * montre que la dernière image). Une frame non datée (mock, banc) allume le
     * direct sans mesure : jamais de chiffre inventé.
     */
    fun noteRendered(seq: Long, frame: GlassesFrame, decodeStartNs: Long, decodeEndNs: Long) {
        markRendered(seq)
        synchronized(timingLock) {
            awaitingNote = false
            pending?.let { linkStats.drop(LinkDrop.CONFLATED) }
            pending = null
            val linkUs = if (seq == lastLinkSeq) lastLinkUs else observeLinkUs(frame)
            if (linkUs < 0L || decodeEndNs < decodeStartNs) return
            val decodeUs = (decodeEndNs - decodeStartNs + NS_PER_US / 2L) / NS_PER_US
            pending = PendingLink(seq, linkUs, decodeUs, decodeEndNs)
        }
    }

    /**
     * Pixel affiché (`onSurfaceTextureUpdated`) : ferme la frame en attente —
     * `aff` = pixel − sortie décodeur — et journalise sous [LINK_TAG] la ligne
     * per-frame ([TimingsText.link]) plus le résumé à 1 Hz au plus
     * ([GlassesLinkStats.line]). [atNs] = 0 = maintenant (même idiome que
     * `ScreenTimings.beginFrame`) ; sans rendu en attente, ne fait rien.
     */
    fun markPixel(atNs: Long = 0L) {
        val nowNs = if (atNs > 0L) atNs else SystemClock.elapsedRealtimeNanos()
        synchronized(timingLock) {
            val done = pending ?: return
            pending = null
            if (nowNs < done.decodeEndNs) return
            val displayUs = (nowNs - done.decodeEndNs + NS_PER_US / 2L) / NS_PER_US
            linkStats.record(done.linkUs, done.decodeUs, displayUs)
            val perceivedUs = done.linkUs + done.decodeUs + displayUs
            Log.i(LINK_TAG, TimingsText.link(done.seq, done.linkUs, done.decodeUs, displayUs, perceivedUs))
            if (nowNs - lastSummaryNs >= SUMMARY_NS) {
                lastSummaryNs = nowNs
                Log.i(LINK_TAG, linkStats.line())
            }
        }
    }

    /** Compte une frame perdue en aval (décodeur muet sur une frame décodable). */
    fun markDrop(cause: LinkDrop) {
        synchronized(timingLock) { linkStats.drop(cause) }
    }

    /** Résumé du lien (`GlassesLink e2e p50/p95/p99 …`), pour l'UI et les tests. */
    fun linkLine(): String = synchronized(timingLock) { linkStats.line() }

    /** Abandons par cause, dans l'ordre de [LinkDrop]. */
    fun linkDrops(): List<Long> = synchronized(timingLock) { linkStats.dropCounts() }

    /** Frames mesurées jusqu'au pixel (les abandons ne comptent pas). */
    val linkSamples: Long get() = synchronized(timingLock) { linkStats.samples }

    /** Oublie la mesure du lien : nouvelle session, nouvelles horloges des deux côtés. */
    private fun resetLinkLocked() {
        clockOffset.reset()
        linkStats.reset()
        awaitingNote = false
        lastLinkSeq = -1L
        lastLinkUs = -1L
        pending = null
        lastSummaryNs = 0L
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
            _renderedSeq.value = null
            synchronized(timingLock) { resetLinkLocked() }
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
