package dev.sam3vision.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

/**
 * Distances du direct, posées sur l'aperçu : pour chaque objet mesuré, une
 * **pastille de verre translucide** — texte blanc, contour et puce de la couleur du masque —
 * reliée par un **fil de rappel** au coin haut-gauche de sa boîte, où un point de
 * la même couleur marque l'objet. Plus de rectangle plein saturé sur la vidéo :
 * l'image reste lisible, la couleur ne sert plus qu'à désigner l'objet.
 *
 * Les positions sont recalculées avec la même transformation que l'aperçu
 * (`ContentScale.Crop`) — [cropTransform], [screenLabels] et [placePills] restent
 * purs et testés en JVM, seule la mise en forme vit ici. Rien à afficher : rien de
 * composé.
 */
@Composable
fun LiveDistanceLayer(
    layer: DistanceLayer?,
    topInset: Dp = TOP_SAFE_INSET,
    withLabel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (layer == null || layer.empty) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        DistancePills(
            layer = layer,
            topInset = topInset,
            withLabel = withLabel,
            boxWidth = constraints.maxWidth.toFloat(),
            boxHeight = constraints.maxHeight.toFloat(),
        )
    }
}

/**
 * Mesure chaque texte, place les pastilles ([placePills]) puis les dessine d'un
 * seul [Canvas] : les fils passent sous les pastilles, jamais l'inverse.
 */
@Composable
private fun DistancePills(
    layer: DistanceLayer,
    topInset: Dp,
    withLabel: Boolean,
    boxWidth: Float,
    boxHeight: Float,
) {
    val measurer = rememberTextMeasurer()
    val metrics = pillMetrics(topInset)
    val transform = cropTransform(layer.width, layer.height, boxWidth, boxHeight)
    val fontSize = (BASE_FONT_SP * sqrt(transform.scale)).coerceIn(MIN_FONT_SP, MAX_FONT_SP).sp
    val style = TextStyle(
        color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
        shadow = Shadow(Color.Black.copy(alpha = TEXT_SHADOW_ALPHA), Offset(0f, metrics.stroke), metrics.stroke * 2f),
    )
    // `measure` refait la mise en page de chaque texte : mémoïsé, il ne rejoue qu'à
    // contenu, style ou géométrie changés — plus une fois par frame du direct. Les clés
    // sont structurelles (`DistanceLayer`, `CropTransform`, `TextStyle` : data classes).
    val labels = remember(layer, transform, withLabel) { screenLabels(layer, transform, withLabel) }
    val texts = remember(measurer, labels, style) { labels.map { measurer.measure(it.text, style, maxLines = 1) } }
    val pills = remember(labels, texts, metrics, boxWidth, boxHeight) {
        val widths = texts.map { it.size.width + metrics.dotRadius * 2f + metrics.dotGap + metrics.padH * 2f }
        val heights = texts.map { it.size.height + metrics.padV * 2f }
        placePills(
            labels = labels,
            widths = widths,
            heights = heights,
            boxWidth = boxWidth,
            boxHeight = boxHeight,
            topInset = metrics.topInset,
            leadGap = metrics.leadGap,
            stackGap = metrics.stackGap,
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        pills.forEach { pill -> drawPillLead(pill, metrics) }
        pills.forEachIndexed { index, pill -> drawPill(pill, texts[index], metrics) }
    }
}

/** Les fils passent tous sous le verre, y compris lorsque les pastilles s'empilent. */
private fun DrawScope.drawPillLead(pill: PillPlacement, metrics: PillMetrics) {
    val color = maskColorAt(pill.colorIndex)
    val dotX = pill.left + metrics.padH + metrics.dotRadius
    drawLine(
        color = color.copy(alpha = LEAD_ALPHA),
        start = Offset(pill.anchorX, pill.anchorY),
        end = Offset(dotX, pill.leadY()),
        strokeWidth = metrics.stroke,
    )
    drawCircle(color = color, radius = metrics.dotRadius * 0.8f, center = Offset(pill.anchorX, pill.anchorY))
}

/** Une pastille : verre transparent, contour SAM, puce et distance à chiffres tabulaires. */
private fun DrawScope.drawPill(pill: PillPlacement, text: TextLayoutResult, metrics: PillMetrics) {
    val color = maskColorAt(pill.colorIndex)
    val dotX = pill.left + metrics.padH + metrics.dotRadius
    drawDistanceBadgeSurface(
        maskColor = color,
        topLeft = Offset(pill.left, pill.top),
        size = Size(pill.width, pill.height),
        corner = metrics.corner,
        stroke = metrics.stroke,
    )
    drawCircle(color, radius = metrics.dotRadius, center = Offset(dotX, pill.top + pill.height / 2f))
    drawText(
        textLayoutResult = text,
        topLeft = Offset(pill.left + metrics.padH + metrics.dotRadius * 2f + metrics.dotGap, pill.top + metrics.padV),
    )
}

/** Les mesures de la pastille, converties une seule fois depuis les dp. */
private data class PillMetrics(
    val topInset: Float,
    val padH: Float,
    val padV: Float,
    val dotRadius: Float,
    val dotGap: Float,
    val leadGap: Float,
    val stackGap: Float,
    val corner: Float,
    val stroke: Float,
)

/** Conversions dp → px de la pastille, une seule fois par composition. */
@Composable
private fun pillMetrics(topInset: Dp): PillMetrics {
    val density = LocalDensity.current
    return with(density) {
        PillMetrics(
            topInset = topInset.toPx(),
            padH = PILL_PAD_H.toPx(),
            padV = PILL_PAD_V.toPx(),
            dotRadius = PILL_DOT_RADIUS.toPx(),
            dotGap = PILL_DOT_GAP.toPx(),
            leadGap = PILL_LEAD_GAP.dp.toPx(),
            stackGap = PILL_STACK_GAP.dp.toPx(),
            corner = PILL_CORNER.toPx(),
            stroke = PILL_STROKE.toPx(),
        )
    }
}

/** Le fil désigne le masque ; l'ombre des lettres garde les distances lisibles sur la vidéo. */
private const val LEAD_ALPHA = 0.85f
private const val TEXT_SHADOW_ALPHA = 0.75f

private val PILL_PAD_H = 9.dp
private val PILL_PAD_V = 5.dp
private val PILL_DOT_RADIUS = 3.dp
private val PILL_DOT_GAP = 5.dp
private val PILL_CORNER = 20.dp
private val PILL_STROKE = 1.2.dp

/**
 * Bandeau du haut (boutons + état + concepts) : aucune pastille ne s'y cache.
 * Les autres hôtes — la fenêtre flottante d'iSee — passent leur propre marge.
 */
private val TOP_SAFE_INSET = 230.dp

/** Corps de la police : adapté à la résolution de l'aperçu, borné pour rester lisible. */
private const val BASE_FONT_SP = 11f
private const val MIN_FONT_SP = 10f
private const val MAX_FONT_SP = 20f
