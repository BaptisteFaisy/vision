package dev.sam3vision.app.live

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject

/**
 * Un point de chemin : centre d'une boîte, normalisé 0..1 dans le repère de
 * l'image analysée (l'overlay le remet à l'échelle de la frame, puis de l'écran).
 */
data class TrailPoint(val x: Float, val y: Float)

/**
 * Chemin d'un objet suivi : son identité stable, la couleur de son masque
 * ([colorIndex] = rang de l'objet dans la réponse) et ses centres successifs,
 * du plus vieux au plus récent.
 */
data class ObjectTrail(
    val id: Int,
    val label: String,
    val colorIndex: Int,
    val points: List<TrailPoint>,
)

/**
 * Couche de chemins d'une frame : un chemin par objet suivi. [empty] = rien à
 * tracer — l'overlay ne dessine rien et le direct reste celui d'avant.
 */
data class TrailLayer(
    val width: Int = 0,
    val height: Int = 0,
    val trails: List<ObjectTrail> = emptyList(),
) {
    val empty: Boolean get() = width <= 0 || height <= 0 || trails.isEmpty()
}

/**
 * Chemins centraux des objets suivis : à chaque frame publiée, le centre de
 * chaque boîte identifiée (voir [dev.sam3vision.core.live.LiveTimeline]) allonge
 * le chemin de sa piste — l'overlay dessine la trajectoire exacte au milieu de
 * chaque objet, dans la couleur de son masque.
 *
 * Une piste que le serveur ne revoit plus garde son chemin jusqu'à [timeoutMs]
 * (un trou d'une frame ne fait pas clignoter la trace), puis est oubliée. Pur
 * JVM : ni Android, ni Compose — testable sans appareil.
 */
class CenterTrails(
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
    private val maxTracks: Int = DEFAULT_MAX_TRACKS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** Une piste : identité, dernier passage, centres successifs (vieux → récent). */
    private class Track(
        val id: Int,
        var label: String,
        var colorIndex: Int,
        var seenAtMs: Long,
    ) {
        val points = ArrayDeque<TrailPoint>()
    }

    private val lock = Any()
    private val tracks = LinkedHashMap<Int, Track>()

    /** Nombre de pistes retenues (oubliées au-delà de [timeoutMs]). */
    val trackCount: Int get() = synchronized(lock) { tracks.size }

    /**
     * Allonge les chemins avec les centres de la frame publiée à [atMs] :
     * [detections] et [seen] sont dans l'ordre des objets de la réponse (le rang
     * donne la couleur), l'id stable vient de [seen]. Renvoie la couche à tracer.
     */
    fun observeFrame(
        atMs: Long,
        width: Int,
        height: Int,
        detections: List<Detection>,
        seen: List<SeenObject?>,
    ): TrailLayer = synchronized(lock) {
        detections.forEachIndexed { index, detection ->
            val id = seen.getOrNull(index)?.id ?: return@forEachIndexed
            val center = detection.center01() ?: return@forEachIndexed
            val track = tracks.getOrPut(id) { Track(id, detection.label, index, atMs) }
            track.label = detection.label
            track.colorIndex = index
            track.seenAtMs = atMs
            track.points.addLast(center)
            while (track.points.size > maxPoints) track.points.removeFirst()
        }
        tracks.values.removeAll { atMs - it.seenAtMs > timeoutMs }
        while (tracks.size > maxTracks) tracks.remove(oldestId())
        if (width <= 0 || height <= 0) return@synchronized TrailLayer()
        TrailLayer(width, height, tracks.values.map { ObjectTrail(it.id, it.label, it.colorIndex, it.points.toList()) })
    }

    /** Oublie tous les chemins : arrêt du direct, nouvelle session. */
    fun reset() {
        synchronized(lock) { tracks.clear() }
    }

    /** Id de la piste vue le moins récemment (évincée quand il y en a trop). */
    private fun oldestId(): Int = tracks.values.minByOrNull { it.seenAtMs }?.id ?: -1

    companion object {
        /** Points retenus par piste : ~24 frames publiées de trajectoire. */
        const val DEFAULT_MAX_POINTS = 24

        /** Pistes retenues : au-delà, la moins récemment vue est oubliée. */
        const val DEFAULT_MAX_TRACKS = 64

        /** Une piste non revue depuis ce délai est oubliée (comme la mémoire). */
        const val DEFAULT_TIMEOUT_MS = 2_500L
    }
}

/** Centre d'une détection, normalisé 0..1 ; `null` si la boîte est illisible. */
private fun Detection.center01(): TrailPoint? {
    val box = normalizedBox() ?: return null
    return TrailPoint((box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f)
}
