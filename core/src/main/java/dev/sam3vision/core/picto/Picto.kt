package dev.sam3vision.core.picto

/**
 * Une image en niveaux de gris, sans dépendance Android : le pont Bitmap vit
 * dans `:app`, et les tests JVM construisent des images synthétiques.
 * [gray] est en lignes (`y * width + x`), chaque valeur entre 0 et 255.
 */
data class GrayImage(val gray: IntArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "dimensions nulles ($width×$height)" }
        require(gray.size == width * height) { "tampon ${gray.size} pour $width×$height" }
    }
}

/**
 * La signature visuelle d'un objet repéré : le centre de sa photo, réduit à
 * [TEMPLATE_EDGE] px. Assez petit pour une recherche multi-échelle à 2 Hz sur
 * le téléphone, assez grand pour distinguer des objets courants.
 */
data class PictoTemplate(val gray: IntArray, val edge: Int) {
    init {
        require(edge > 0) { "côté nul" }
        require(gray.size == edge * edge) { "tampon ${gray.size} pour $edge×$edge" }
    }
}

/** Côté du gabarit, en px (voir [PictoTemplate]). */
const val TEMPLATE_EDGE = 24

/**
 * Un objet reconnu sur une frame : son rectangle en coordonnées normalisées
 * (0..1, origine en haut à gauche) et son score de corrélation (0..1).
 */
data class PictoHit(
    val pictoId: String,
    val label: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val score: Float,
)
