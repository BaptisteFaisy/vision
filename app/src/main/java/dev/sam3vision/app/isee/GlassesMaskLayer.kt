package dev.sam3vision.app.isee

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.sam3vision.app.cropTransform
import kotlin.math.roundToInt

/**
 * Masques SAM sur la rue des lunettes : les mêmes [Bitmap] que la sonde produit pour le
 * téléphone, posés avec le même cadrage `Crop` que les pastilles ([cropTransform]) —
 * l'image analysée a le rapport de la rue (photo de ce qui s'affiche), le recalage est
 * donc une simple échelle. Rien à afficher : rien de composé.
 */
@Composable
internal fun GlassesMaskLayer(
    masks: List<Bitmap>,
    sentWidth: Int,
    sentHeight: Int,
    modifier: Modifier = Modifier,
) {
    if (masks.isEmpty() || sentWidth <= 0 || sentHeight <= 0) return
    // Convertis une fois par paquet (motif `SharedUi`) : plus de `asImageBitmap` par frame.
    val images = remember(masks) { masks.map { it.asImageBitmap() } }
    Canvas(modifier.fillMaxSize()) {
        val map = cropTransform(sentWidth, sentHeight, size.width, size.height)
        images.forEach { image ->
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(map.offsetX.roundToInt(), map.offsetY.roundToInt()),
                dstSize = IntSize((image.width * map.scale).roundToInt(), (image.height * map.scale).roundToInt()),
            )
        }
    }
}
