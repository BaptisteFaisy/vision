package dev.sam3vision.core.model

/**
 * Un objet suivi par le tracker vidéo (`sam3-track`) : son [id] est **stable**
 * d'une frame à l'autre tant que la session vit — c'est ce qui permet de dire
 * « la même personne » plutôt que « une personne ».
 */
data class TrackedObject(
    val id: Int,
    val label: String,
    val score: Float,
    val box: List<Float>,
    val maskPngB64: String,
)

/** Une frame du tracker : objets identifiés, à la taille annoncée par la session. */
data class TrackFrame(
    val width: Int,
    val height: Int,
    val objects: List<TrackedObject>,
)
