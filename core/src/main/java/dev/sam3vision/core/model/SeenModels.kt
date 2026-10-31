package dev.sam3vision.core.model

/**
 * Une détection vue par la caméra, avant normalisation : boîte en pixels de
 * l'image source ([width] × [height]).
 *
 * [maskBox] est la bbox **exclusive** du masque (`[x1, y1, x2, y2]`, pixels
 * frame, même convention que [box]), posée par `toDetectionsWithExtents` quand
 * le masque est fiable (compact : poly/bits/rle décodé, non fragmenté) —
 * `null` sinon (PNG non décodé sur ce chemin, `none`, masque troué). Le sténopé
 * et le plan de sol la préfèrent à [box] : hauteur vraie de l'objet, ligne des
 * pieds exacte. L'overlay et l'appariement gardent [box], la boîte détecteur.
 */
data class Detection(
    val label: String,
    val score: Float,
    val box: List<Float>,
    val width: Int,
    val height: Int,
    val maskBox: List<Float>? = null,
) {
    /** Boîte normalisée 0..1 (`x1,y1,x2,y2`) ; `null` si la taille est inconnue. */
    fun normalizedBox(): List<Float>? {
        if (width <= 0 || height <= 0 || box.size < 4) return null
        val x1 = box[0] / width
        val y1 = box[1] / height
        val x2 = box[2] / width
        val y2 = box[3] / height
        return listOf(x1, y1, x2, y2)
    }
}

/** Un objet observé, avec l'identité stable attribuée par la mémoire du direct. */
data class SeenObject(
    val id: Int,
    val label: String,
    val score: Float,
    val box: List<Float>,
)

/** Une image du direct observée : horodatage + objets identifiés. */
data class SeenFrame(
    val atMs: Long,
    val objects: List<SeenObject>,
)

/** Ce qu'on sait d'un objet suivi : apparition, dernière vue, position, occurrences. */
data class SeenTrack(
    val id: Int,
    val label: String,
    val firstAtMs: Long,
    val lastAtMs: Long,
    val score: Float,
    val centerX: Float,
    val centerY: Float,
    val frames: Int,
)

/** Résumé d'une fenêtre : ce que la caméra a vu entre [fromMs] et [toMs]. */
data class TimelineSummary(
    val fromMs: Long,
    val toMs: Long,
    val tracks: List<SeenTrack>,
) {
    /** Vrai si rien n'a été vu dans la fenêtre. */
    val empty: Boolean get() = tracks.isEmpty()
}

/** Les objets d'une frame `/live` deviennent des détections (boîte en pixels). */
@JvmName("segObjectsToDetections")
fun List<SegObject>.toDetections(width: Int, height: Int): List<Detection> =
    map { Detection(it.label, it.score, it.box, width, height) }

/** Les objets d'une frame de tracker deviennent des détections (boîte en pixels). */
@JvmName("trackedObjectsToDetections")
fun List<TrackedObject>.toDetections(width: Int, height: Int): List<Detection> =
    map { Detection(it.label, it.score, it.box, width, height) }
