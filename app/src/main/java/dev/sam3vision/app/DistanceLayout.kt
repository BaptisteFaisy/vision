package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.DistanceText

/**
 * Cadrage `ContentScale.Crop` : une seule échelle, puis un décalage de centrage.
 * Reproduire ce calcul côté Compose garantit que les étiquettes de distance
 * tombent sur les mêmes pixels que les masques, quel que soit l'écran.
 */
data class CropTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val boxWidth: Float = 0f,
    val boxHeight: Float = 0f,
) {

    /** Abscisse écran d'un point de l'image analysée. */
    fun screenX(frameX: Float): Float = frameX * scale + offsetX

    /** Ordonnée écran d'un point de l'image analysée. */
    fun screenY(frameY: Float): Float = frameY * scale + offsetY
}

/**
 * Transformation d'une image `[imageWidth]`×`[imageHeight]` dans un cadre
 * `[boxWidth]`×`[boxHeight]` en `Crop` : l'image remplit le cadre en gardant son
 * rapport et déborde, centrée. Cadre ou image dégénéré : transformation neutre.
 */
fun cropTransform(imageWidth: Int, imageHeight: Int, boxWidth: Float, boxHeight: Float): CropTransform {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) {
        return CropTransform(1f, 0f, 0f)
    }
    val scale = maxOf(boxWidth / imageWidth, boxHeight / imageHeight)
    return CropTransform(
        scale = scale,
        offsetX = (boxWidth - imageWidth * scale) / 2f,
        offsetY = (boxHeight - imageHeight * scale) / 2f,
        boxWidth = boxWidth,
        boxHeight = boxHeight,
    )
}

/**
 * Étiquette de distance prête à poser : le **coin haut-gauche** de sa boîte
 * (`[x]`, `[y]`, px écran). La pastille s'aligne sur ce coin et un fil de rappel
 * la relie au point de repère posé dessus — c'est ce fil qui dit à quel objet
 * appartient la distance quand deux boîtes se touchent.
 */
data class ScreenLabel(val text: String, val x: Float, val y: Float, val colorIndex: Int)

/** Une pastille posée à l'écran : son rectangle, et l'ancre du fil sur sa boîte. */
data class PillPlacement(
    val text: String,
    val colorIndex: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val anchorX: Float,
    val anchorY: Float,
)

/** Écart (px écran) entre le bord haut de la boîte et le bas de la pastille. */
const val PILL_LEAD_GAP = 22f

/** Écart (px écran) entre deux pastilles empilées. */
const val PILL_STACK_GAP = 8f

/**
 * Étiquettes des objets mesurés de [layer], dans l'ordre des objets de la frame
 * (la couleur suit donc le masque). Une boîte illisible est ignorée.
 *
 * [withLabel] préfixe la pastille du concept (« personne ≈ 3,9 m ») : les hôtes
 * sans masques — la photo d'iSee — n'ont que ce texte pour savoir de quel objet
 * elle parle.
 *
 * [followed] non vide : seules les pastilles des concepts suivis (comparaison
 * insensible à la casse) ; vide : tout s'affiche (pas de suivi explicite —
 * la voix et le guidage lisent toujours toute la couche).
 */
fun screenLabels(
    layer: DistanceLayer,
    transform: CropTransform,
    withLabel: Boolean = false,
    followed: Set<String> = emptySet(),
): List<ScreenLabel> = layer.readings.mapIndexedNotNull { index, reading ->
    if (reading.box.size < 4) return@mapIndexedNotNull null
    if (followed.isNotEmpty() && followed.none { it.equals(reading.label.trim(), ignoreCase = true) }) {
        return@mapIndexedNotNull null
    }
    ScreenLabel(
        text = labelText(reading, withLabel),
        x = transform.screenX(reading.box[0]).coerceIn(0f, transform.boxWidth),
        y = transform.screenY(reading.box[1]).coerceIn(0f, transform.boxHeight),
        colorIndex = index,
    )
}

/**
 * Place les pastilles : chacune au-dessus du coin de sa boîte, ramenée dans le
 * cadre, jamais sous le bandeau du haut ([topInset]), et **descendue** tant qu'elle
 * recouvre une pastille déjà posée. Deux objets voisins gardent ainsi deux
 * distances lisibles, reliées chacune à sa boîte par son fil. Fonction pure,
 * testée en JVM ([DistanceLayoutTest]) — seul le dessin vit dans l'overlay.
 */
fun placePills(
    labels: List<ScreenLabel>,
    widths: List<Float>,
    heights: List<Float>,
    boxWidth: Float,
    boxHeight: Float,
    topInset: Float,
    leadGap: Float = PILL_LEAD_GAP,
    stackGap: Float = PILL_STACK_GAP,
): List<PillPlacement> {
    val placed = ArrayList<PillPlacement>(labels.size)
    labels.forEachIndexed { index, label ->
        val width = widths.getOrElse(index) { 0f }
        val height = heights.getOrElse(index) { 0f }
        val left = label.x.coerceIn(0f, maxOf(0f, boxWidth - width))
        val top = stackTop(left, label.y - leadGap - height, width, height, placed, boxHeight, topInset, stackGap)
        placed += PillPlacement(
            text = label.text,
            colorIndex = label.colorIndex,
            left = left,
            top = top,
            width = width,
            height = height,
            anchorX = label.x,
            anchorY = label.y,
        )
    }
    return placed
}

/**
 * Ordonnée d'une pastille : la place voulue, bornée par le bandeau du haut et le
 * bas du cadre, puis poussée vers le bas par pas de `hauteur + [stackGap]` tant
 * qu'elle chevauche une pastille déjà posée.
 */
private fun stackTop(
    left: Float,
    wanted: Float,
    width: Float,
    height: Float,
    placed: List<PillPlacement>,
    boxHeight: Float,
    topInset: Float,
    stackGap: Float,
): Float {
    val bottom = maxOf(topInset, boxHeight - height)
    var top = wanted.coerceIn(minOf(topInset, bottom), bottom)
    var guard = 0
    while (guard++ <= placed.size && placed.any { it.overlaps(left, top, width, height, stackGap) }) {
        top += height + stackGap
    }
    return top.coerceAtMost(bottom)
}

/** Vrai si une pastille de `[left]`/`[top]` tombe dans l'emprise d'une pastille posée. */
private fun PillPlacement.overlaps(left: Float, top: Float, width: Float, height: Float, gap: Float): Boolean =
    left < this.left + this.width + gap &&
        this.left < left + width + gap &&
        top < this.top + this.height + gap &&
        this.top < top + height + gap

/** Ordonnée où le fil touche la pastille : son bord le plus proche du point de repère. */
fun PillPlacement.leadY(): Float = if (top + height <= anchorY) top + height else top

/** Texte d'une pastille : la distance seule, ou le concept borné puis la distance. */
fun labelText(reading: DistanceReading, withLabel: Boolean): String {
    val distance = DistanceText.approximate(reading.meters)
    if (!withLabel) return distance
    val concept = reading.label.trim().take(MAX_CONCEPT_CHARS).ifEmpty { "objet" }
    return "$concept $distance"
}

/** Longueur maximale du concept dans une pastille : au-delà, la pastille déborde. */
private const val MAX_CONCEPT_CHARS = 14
