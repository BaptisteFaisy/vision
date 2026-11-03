package dev.sam3vision.app.glasses

import android.os.SystemClock
import android.util.Log
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.motion.addMotion
import com.meta.wearable.dat.motion.removeMotion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.GlassesClockOffset
import dev.sam3vision.core.live.GlassesLinkStats
import dev.sam3vision.core.live.LinkDrop
import dev.sam3vision.core.live.TimingsText
import kotlinx.coroutines.CoroutineScope
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

/**
 * Géométrie de la dernière photo SAM : taille vidéo décodée + taille de la vue
 * affichée. La photo part au ratio de la vue ; le sténopé en déduit le recadrage
 * « remplit la vue » subi par le flux (`CropGeometry.fillViewCrop`, D2).
 */
data class SnapshotGeometry(val videoW: Int, val videoH: Int, val viewW: Int, val viewH: Int)

/** Journal du direct (recette : `logcat -s GlassesVideo`). */
private const val TAG = "GlassesVideo"

/** Journal de la mesure du lien (recette B1/B2 : `logcat -s GlassesLink`). */
private const val LINK_TAG = "GlassesLink"

/** Âge maximal d'une pose tête crue fraîche (10 Hz nominaux, large comme `PitchMeter`). */
const val HEAD_POSE_STALE_AFTER_MS = 1_000L

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

    /**
     * La rue ne regarde plus : la géométrie de la dernière photo est oubliée.
     *
     * ⚠️ Il n'y a plus de `takeSnapshot` : photographier la `TextureView`
     * (`getBitmap`) coûtait 3 à 15 ms de fil UI par poussée. La sonde décode
     * désormais la poussée elle-même (`GlassesBackgroundFrames`), hors fil UI.
     */
    fun detachSnapshot() {
        snapshotGeometryField = null
    }

    @Volatile
    private var snapshotGeometryField: SnapshotGeometry? = null

    /**
     * Focale du flux des lunettes (même étage que `chooseLens` voie lunettes :
     * forcé > mire > profil modèle > repli — tous décrivent le flux portrait
     * 9:16, jamais la photo). Posée par D1 quand le direct vit, `null` hors
     * lunettes. Le pont E5 la recadre par photo (`afterCrop`) : pas de double
     * compte, la mire et le forcé sont eux aussi pré-crop (portrait).
     */
    @Volatile
    var glassesStreamLens: LensSpec? = null

    /** La rue photographiée note la géométrie de sa photo (lue à la photo suivante). */
    fun noteSnapshotGeometry(videoW: Int, videoH: Int, viewW: Int, viewH: Int) {
        snapshotGeometryField = SnapshotGeometry(videoW, videoH, viewW, viewH)
    }

    /** Géométrie de la dernière photo, `null` si la rue ne regarde pas. */
    fun snapshotGeometry(): SnapshotGeometry? = snapshotGeometryField

    /**
     * Paramètres H.265 hors bande (VPS/SPS/PPS) : le flux simulé n'envoie que des tranches,
     * le décodeur les prend ici faute de config dans le flux. Toujours `null` sur des
     * lunettes réelles (la config voyage dans le flux).
     */
    private val _headPose = MutableStateFlow<CameraPose?>(null)

    /**
     * Dernière pose tête DAT Motion (`null` = inconnue : Motion refusée,
     * orientation absente, erreur, ou session fermée). Écrite par
     * [GlassesHeadPose], lue par la sonde (`observeHeadPose`, `PitchMeter`) :
     * même motif passerelle que les frames, sans référence au SDK ni à l'UI.
     */
    val headPose: StateFlow<CameraPose?> = _headPose.asStateFlow()

    /** Fraîcheur de [headPose] (ms wall-clock téléphone, `0` = jamais). */
    @Volatile
    var headPoseAtMs: Long = 0L
        private set

    /** Publie la pose tête (ou son absence) en la datant. */
    fun publishHeadPose(pose: CameraPose?, nowMs: Long = System.currentTimeMillis()) {
        headPoseAtMs = nowMs
        _headPose.value = pose
    }

    /**
     * Pose tête si fraîche (même horizon 1 s que `PitchMeter.STALE_AFTER_MS`),
     * sinon `null` : une pose périmée ne rouvre jamais la branche sol.
     */
    fun freshHeadPose(nowMs: Long = System.currentTimeMillis()): CameraPose? {
        val pose = _headPose.value ?: return null
        if (!pose.isValid) return null
        if (nowMs - headPoseAtMs > HEAD_POSE_STALE_AFTER_MS) return null
        return pose
    }

    private var headPoseFeed: GlassesHeadPose? = null

    /**
     * Attache la pose tête DAT Motion sur la session (pin 1.0.0, 10 Hz) : un
     * refus (canal bêta, paire sans Motion) publie null et la sonde garde le
     * sténopé seul. Motif passerelle comme les frames et les notes.
     */
    fun attachHeadPose(
        scope: CoroutineScope,
        onNote: (GlassesMessage) -> Unit,
        session: DeviceSession,
        eyeHeightM: () -> Float = { CameraPose.GLASSES_HEIGHT_M },
    ) {
        headPoseFeed = GlassesHeadPose(scope, onNote, eyeHeightM = eyeHeightM).also {
            it.start(
                attach = { session.addMotion(MotionConfiguration()).getOrNull() },
                detach = { session.removeMotion() },
            )
        }
    }

    /** Détache la pose tête (oublie la pose, jamais de valeur périmée). */
    fun detachHeadPose() {
        headPoseFeed?.stop()
        headPoseFeed = null
        publishHeadPose(null)
    }

    @Volatile
    var fallbackCsd: ByteArray? = null

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

    /** Mesure figée à la poussée ([linkSeq] sans configs, [linkUs] radio), relue au rendu. */
    private data class LinkNote(val linkSeq: Long, val linkUs: Long)

    private val timingLock = Any()

    /** Nanosecondes par microseconde (tous les étages du lien sont en µs). */
    private const val NS_PER_US = 1_000L

    /** Cadence du résumé [LINK_TAG] : 1 Hz au plus, comme la recette l'exige. */
    private const val SUMMARY_NS = 1_000_000_000L

    /**
     * Une ligne détaillée [TimingsText.link] toutes les N frames : à 15 i/s,
     * ~3 par seconde — la jointure E2E (`parse_glasses_e2e.py`, N >= 100) en
     * manquait à 1/s (30 jointes/120 s en recette 15b). Le format ne bouge pas
     * ; formatage + I/O logcat restent échantillonnés, pas par frame.
     */
    private const val LINK_LINE_EVERY = 5L

    /** Poussées rejoinables par un rendu tardif (~1 s à 15 i/s), au-delà conflées. */
    internal const val LINK_NOTE_WINDOW = 16

    /** Rendus en attente de leur pixel (~0,5 s à 15 i/s), au-delà le plus vieux conflé. */
    internal const val PENDING_WINDOW = 8

    private val clockOffset = GlassesClockOffset()
    private val linkStats = GlassesLinkStats()

    /** Notes du lien par `seq` poussé, en ordre de poussée (borné : [LINK_NOTE_WINDOW]). */
    private val linkNotes = LinkedHashMap<Long, LinkNote>()

    /** Compteur du lien : une poussée décodable = +1 (configs et dégénérées exclues). */
    private var linkSeq = 0L

    /** Plus grand `linkSeq` compté (rendu ou évincé) : borne basse des écarts, sans double. */
    private var accountedLinkSeq = 0L

    /** Rendus datés en attente de leur pixel, dans l'ordre (borné : [PENDING_WINDOW]). */
    private val pendings: ArrayDeque<PendingLink> = ArrayDeque()

    /** Conflations côté pixel (file pleine), pour partager [LinkDrop.CONFLATED]. */
    private var conflatedPixel = 0L

    private var lastSummaryNs = 0L

    /** Frames mesurées depuis la remise à zéro : cadence la ligne détaillée. */
    private var linkLineTicks = 0L

    /**
     * Arrivée d'une poussée : décalage pts ↔ téléphone (B1), note du lien figée,
     * abandons d'amont. La conflation se compte au rendu ([noteRendered]), pas ici :
     * pousser pendant un décodage ne perd rien, seul l'écart de `seq` tranche.
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
        linkSeq += 1
        linkNotes[pushedSeq] = LinkNote(linkSeq, observeLinkUs(frame))
        while (linkNotes.size > LINK_NOTE_WINDOW) {
            val oldest = linkNotes.entries.first()
            linkNotes.remove(oldest.key)
            if (oldest.value.linkSeq > accountedLinkSeq) {
                accountedLinkSeq = oldest.value.linkSeq
                linkStats.drop(LinkDrop.CONFLATED)
            }
        }
    }

    /** Retard variable radio (µs) d'une frame datée, `-1` si elle ne l'est pas (mock, banc). */
    private fun observeLinkUs(frame: GlassesFrame): Long {
        if (frame.arrivedAtNs <= 0L) return -1L
        val jitterNs = clockOffset.observe(frame.arrivedAtNs, frame.presentationTimeUs) ?: return -1L
        return (jitterNs + NS_PER_US / 2L) / NS_PER_US
    }

    /**
     * Frame rendue ([decodeStartNs]/[decodeEndNs] monotones) : direct allumé, poussées
     * sautées conflées (l'écart de `linkSeq`, ni plus ni moins), rendu filé vers son
     * pixel. `seq` inconnu (boîte d'avant `clear`) : lien relu sans écart ; frame non
     * datée (mock, banc) : direct sans mesure, jamais de chiffre inventé.
     */
    fun noteRendered(seq: Long, frame: GlassesFrame, decodeStartNs: Long, decodeEndNs: Long) {
        markRendered(seq)
        synchronized(timingLock) {
            val linkUs = linkNotes.remove(seq)?.let { note ->
                repeat((note.linkSeq - accountedLinkSeq - 1L).coerceAtLeast(0L).toInt()) {
                    linkStats.drop(LinkDrop.CONFLATED)
                }
                if (note.linkSeq > accountedLinkSeq) accountedLinkSeq = note.linkSeq
                note.linkUs
            } ?: observeLinkUs(frame)
            if (linkUs < 0L || decodeEndNs < decodeStartNs) return
            val decodeUs = (decodeEndNs - decodeStartNs + NS_PER_US / 2L) / NS_PER_US
            while (pendings.size >= PENDING_WINDOW) {
                pendings.removeFirst()
                conflatedPixel += 1
                linkStats.drop(LinkDrop.CONFLATED)
            }
            pendings.addLast(PendingLink(seq, linkUs, decodeUs, decodeEndNs))
        }
    }

    /**
     * Pixel affiché : ferme le plus vieux rendu en attente (`aff` = pixel − sortie
     * décodeur), journalise la détaillée 1/[LINK_LINE_EVERY] et le résumé à 1 Hz.
     * [atNs] = 0 = maintenant ; sans attente, ou pixel antérieur au rendu, rien.
     */
    fun markPixel(atNs: Long = 0L) {
        val nowNs = if (atNs > 0L) atNs else SystemClock.elapsedRealtimeNanos()
        synchronized(timingLock) {
            val done = pendings.firstOrNull() ?: return
            if (nowNs < done.decodeEndNs) return
            pendings.removeFirst()
            val displayUs = (nowNs - done.decodeEndNs + NS_PER_US / 2L) / NS_PER_US
            linkStats.record(done.linkUs, done.decodeUs, displayUs)
            // La ligne par frame ne servait qu'au parseur : une sur N suffit, et
            // `perceivedUs` ne se calcule donc plus à chaque frame (il ne nourrit
            // que ce journal-là).
            if (linkLineTicks % LINK_LINE_EVERY == 0L) {
                val perceivedUs = done.linkUs + done.decodeUs + displayUs
                Log.i(LINK_TAG, TimingsText.link(done.seq, done.linkUs, done.decodeUs, displayUs, perceivedUs))
            }
            linkLineTicks += 1L
            if (nowNs - lastSummaryNs >= SUMMARY_NS) {
                lastSummaryNs = nowNs
                Log.i(LINK_TAG, linkStats.line())
                Log.i(LINK_TAG, linkDropLineLocked())
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

    /**
     * Abandons par cause, `attente` = avant rendu, `pixel` = avant affichage
     * (`throttle`/`encode` vivent dans la pompe, pas ici). Pour recette et tests.
     */
    fun linkDropLine(): String = synchronized(timingLock) { linkDropLineLocked() }

    private fun linkDropLineLocked(): String {
        val drops = linkStats.dropCounts()
        val conflated = drops[LinkDrop.CONFLATED.ordinal]
        return "GlassesLink drops conflated=$conflated" +
            " (attente=${conflated - conflatedPixel} pixel=$conflatedPixel)" +
            " config=${drops[LinkDrop.CODEC_CONFIG.ordinal]}" +
            " dim=${drops[LinkDrop.DIMENSIONS.ordinal]}" +
            " decode=${drops[LinkDrop.DECODE_FAIL.ordinal]}"
    }

    /** Frames mesurées jusqu'au pixel (les abandons ne comptent pas). */
    val linkSamples: Long get() = synchronized(timingLock) { linkStats.samples }

    /** Oublie la mesure du lien : nouvelle session, nouvelles horloges des deux côtés. */
    private fun resetLinkLocked() {
        clockOffset.reset()
        linkStats.reset()
        linkNotes.clear()
        linkSeq = 0L
        accountedLinkSeq = 0L
        pendings.clear()
        conflatedPixel = 0L
        lastSummaryNs = 0L
        // Nouvelle session : la première frame rendue redonne une ligne détaillée.
        linkLineTicks = 0L
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
            snapshotGeometryField = null
            _renderedSeq.value = null
            synchronized(timingLock) { resetLinkLocked() }
        }
    }

    /** Session terminée : oubli complet, comme si rien n'avait streamé. */
    fun clear() {
        setStreaming(false)
        // Le format H.265 est mémorisé par session : la suivante peut parler
        // un autre format (réel vs simulé), on oublie avant de la lire.
        resetAnnexBMemo()
        // Ici, pas dans setStreaming(false) : le flux émet `false` en ouverture de
        // session, pile entre la pose du repli (mock) et sa lecture (décodeur).
        fallbackCsd = null
        // La paire peut changer entre deux sessions : la focale est reposée par D1.
        glassesStreamLens = null
        // ...et la pose tête (jamais de pose périmée d'une session à l'autre).
        detachHeadPose()
    }

    /** Taille annoncée par le décodeur (un changement de résolution recadre la vue). */
    fun onVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) _videoSize.value = VideoSize(width, height)
    }
}
