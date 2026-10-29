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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.picto.PictoHit

/** Un repère à l'écran : son rectangle (px écran) et le nom de l'objet. */
data class PictoRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String)

/**
 * Repères reconnus sur le direct : un cadre jaune épais + le nom par objet
 * retrouvé. Positions recalculées avec la même transformation que l'aperçu
 * (`Crop`) — [pictoRects] reste pur et testé en JVM, seule la mise en forme
 * vit ici. Rien à afficher : rien de composé.
 */
@Composable
fun LivePictoOverlay(layer: PictoOverlay?) {
    if (layer == null || layer.empty) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val transform = cropTransform(layer.width, layer.height, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val rects = remember(layer, transform) { pictoRects(layer.hits, layer.width, layer.height, transform) }
        val measurer = rememberTextMeasurer()
        val style = TextStyle(
            color = Color.Yellow, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 3f), 6f),
        )
        Canvas(Modifier.fillMaxSize()) {
            rects.forEach { rect ->
                drawRect(Color.Yellow, Offset(rect.left, rect.top), Size(rect.right - rect.left, rect.bottom - rect.top), style = Stroke(width = 5f))
                val text = measurer.measure(rect.label, style, maxLines = 1)
                drawText(textLayoutResult = text, topLeft = Offset(rect.left, rect.top - text.size.height - 6f))
            }
        }
    }
}

/**
 * Détections normalisées (0..1) → rectangles écran, via la transformation
 * `Crop` de l'aperçu. Pur et testé en JVM.
 */
fun pictoRects(hits: List<PictoHit>, width: Int, height: Int, transform: CropTransform): List<PictoRect> =
    hits.map {
        PictoRect(
            left = transform.screenX(it.x * width),
            top = transform.screenY(it.y * height),
            right = transform.screenX((it.x + it.w) * width),
            bottom = transform.screenY((it.y + it.h) * height),
            label = it.label,
        )
    }
