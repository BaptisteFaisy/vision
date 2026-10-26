package dev.sam3vision.core.live

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenFrame
import dev.sam3vision.core.model.SeenObject
import dev.sam3vision.core.model.SeenTrack
import dev.sam3vision.core.model.TimelineSummary
import kotlin.math.abs

/**
 * Mémoire courte du direct : les frames observées sont conservées [retentionMs]
 * (60 s par défaut) et chaque objet reçoit une **identité stable** — « la même
 * personne » d'une image à l'autre, tant qu'il reste à la même place.
 *
 * L'identité est attribuée par le label + la proximité du centre horizontal :
 * un objet qui revient au même endroit retrouve son identifiant, un objet qui
 * apparaît ailleurs en reçoit un nouveau.
 *
 * Sûre entre threads : l'analyseur écrit, l'UI lit.
 */
class LiveTimeline(
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val matchRadius: Float = DEFAULT_MATCH_RADIUS,
    private val trackTimeoutMs: Long = DEFAULT_TRACK_TIMEOUT_MS,
) {

    /** Piste suivie : identité, position et dernière apparition. */
    private class Track(
        val id: Int,
        val label: String,
        var centerX: Float,
        var centerY: Float,
        var lastAtMs: Long,
        var score: Float,
    )

    private val frames = ArrayDeque<SeenFrame>()
    private val tracks = LinkedHashMap<Int, Track>()
    private var nextId = 1

    /** Nombre de frames conservées. */
    val size: Int get() = synchronized(this) { frames.size }

    /**
     * Enregistre une frame observée : identité des objets, puis purge de l'ancien.
     * Renvoie l'identité attribuée **dans l'ordre des détections** (`null` pour une
     * détection inexploitable) : les couches qui annotent la frame, comme les
     * distances, rapprochent ainsi une boîte de sa piste.
     */
    fun record(atMs: Long, detections: List<Detection>): List<SeenObject?> {
        synchronized(this) {
            val identified = detections.map { identify(it, atMs) }
            frames.addLast(SeenFrame(atMs, identified.filterNotNull()))
            trim(atMs)
            return identified
        }
    }

    /** Résumé de la fenêtre [fromMs, toMs]. */
    fun summary(fromMs: Long, toMs: Long): TimelineSummary {
        synchronized(this) {
            val kept = frames.filter { it.atMs in fromMs..toMs }
            return TimelineSummary(fromMs, toMs, summarize(kept))
        }
    }

    /** Résumé des [windowMs] qui précèdent [nowMs]. */
    fun recent(nowMs: Long, windowMs: Long): TimelineSummary = summary(nowMs - windowMs, nowMs)

    /** Vide la mémoire (nouvelle session de direct). */
    fun clear() {
        synchronized(this) {
            frames.clear()
            tracks.clear()
            nextId = 1
        }
    }

    /**
     * Attribue (ou réutilise) l'identité d'un objet : même label et centre
     * proche ⇒ même piste ; sinon nouvelle piste. `null` si la détection est
     * inexploitable (boîte illisible, label vide).
     */
    private fun identify(detection: Detection, atMs: Long): SeenObject? {
        val box = detection.normalizedBox() ?: return null
        if (detection.label.isBlank()) return null
        val centerX = (box[0] + box[2]) / 2f
        val centerY = (box[1] + box[3]) / 2f
        val existing = tracks.values.lastOrNull { track ->
            track.label == detection.label &&
                atMs - track.lastAtMs <= trackTimeoutMs &&
                abs(track.centerX - centerX) <= matchRadius
        }
        if (existing != null) {
            existing.lastAtMs = atMs
            existing.centerX = centerX
            existing.centerY = centerY
            existing.score = maxOf(existing.score, detection.score)
            return SeenObject(existing.id, existing.label, existing.score, box)
        }
        val id = nextId++
        tracks[id] = Track(id, detection.label, centerX, centerY, atMs, detection.score)
        return SeenObject(id, detection.label, detection.score, box)
    }

    /** Agrège les frames d'une fenêtre : une ligne par objet suivi. */
    private fun summarize(kept: List<SeenFrame>): List<SeenTrack> {
        val byId = LinkedHashMap<Int, SeenTrack>()
        kept.forEach { frame ->
            frame.objects.forEach { obj ->
                val previous = byId[obj.id]
                byId[obj.id] = previous?.copy(
                    lastAtMs = frame.atMs,
                    score = maxOf(previous.score, obj.score),
                    centerX = centerX(obj.box),
                    centerY = centerY(obj.box),
                    frames = previous.frames + 1,
                ) ?: SeenTrack(
                    id = obj.id,
                    label = obj.label,
                    firstAtMs = frame.atMs,
                    lastAtMs = frame.atMs,
                    score = obj.score,
                    centerX = centerX(obj.box),
                    centerY = centerY(obj.box),
                    frames = 1,
                )
            }
        }
        return byId.values.sortedBy { it.firstAtMs }
    }

    /** Purge : frames trop anciennes, trop nombreuses, pistes oubliées. */
    private fun trim(atMs: Long) {
        while (frames.size > maxFrames) frames.removeFirst()
        val limit = atMs - retentionMs
        while (frames.isNotEmpty() && frames.first().atMs < limit) frames.removeFirst()
        tracks.values.removeAll { atMs - it.lastAtMs > trackTimeoutMs }
    }

    private fun centerX(box: List<Float>): Float = if (box.size >= 4) (box[0] + box[2]) / 2f else 0f

    private fun centerY(box: List<Float>): Float = if (box.size >= 4) (box[1] + box[3]) / 2f else 0f

    companion object {
        /** Fenêtre retenue par défaut : 60 s de mémoire du direct. */
        const val DEFAULT_RETENTION_MS = 60_000L

        /** Nombre maximal de frames conservées (au-delà, les plus anciennes partent). */
        const val DEFAULT_MAX_FRAMES = 600

        /** Distance maximale (fraction de largeur) pour retrouver la même piste. */
        const val DEFAULT_MATCH_RADIUS = 0.18f

        /** Au-delà, une piste est oubliée : l'objet qui revient est un nouvel objet. */
        const val DEFAULT_TRACK_TIMEOUT_MS = 2_500L
    }
}
