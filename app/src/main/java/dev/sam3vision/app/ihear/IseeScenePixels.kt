package dev.sam3vision.app.ihear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import dev.sam3vision.core.iseedesign.pixelTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Le remplissage de la bulle par les pixels de l'image montrée : la même image est
 * échantillonnée en [GRID_COLS]×[GRID_ROWS] cellules (moyennes, pas de gros carré
 * pixellisé brut), puis chaque cellule **vole** de sa case vers sa place finale —
 * décalée depuis le bord par [pixelTrip] — au fil d'un front de remplissage.
 * Sous la mosaïque, l'image vive reste visible dans ses trous : la bulle « se
 * remplit » visuellement sans jamais se figer.
 *
 * Les couleurs viennent d'un [Bitmap] source (aperçu caméra ou photo) échantillonné
 * dehors : le composable ne connaît que des Int ARGB, la routine `sampleGrid` de `:core` est
 * pure et testée sur JVM. Le rééchantillonnage tourne en continu ([SAMPLE_MS]) sans
 * jamais figer l'animation : la grille se met à jour pendant que le front avance.
 */
internal const val GRID_COLS = 26
internal const val GRID_ROWS = 16
/** Période de rééchantillonnage de la grille pendant l'enregistrement (ms). */
internal const val SAMPLE_MS = 160L

/** État de la mosaïque : les couleurs courantes, posées par le producteur. */
internal data class PixelGrid(
    val colors: IntArray,
    val cols: Int,
    val rows: Int,
    /** Vrai dès qu'un premier échantillon est là (sinon la mosaïque reste vide). */
    val ready: Boolean = colors.isNotEmpty(),
)

/** Échantillonne un bitmap caméra en grille de couleurs. `null` = rien (grise). */
internal fun samplePreviewGrid(bitmap: Bitmap?): PixelGrid {
    if (bitmap == null || bitmap.isRecycled) return PixelGrid(IntArray(0), GRID_COLS, GRID_ROWS)
    val small = Bitmap.createScaledBitmap(bitmap, GRID_COLS, GRID_ROWS, true)
    val pixels = IntArray(GRID_COLS * GRID_ROWS)
    small.getPixels(pixels, 0, GRID_COLS, 0, 0, GRID_COLS, GRID_ROWS)
    if (small !== bitmap) small.recycle()
    return PixelGrid(pixels, GRID_COLS, GRID_ROWS)
}

/** Échantillonne la photo du double tap à partir de son fichier (IO léger, 22×22). */
internal suspend fun samplePhotoGrid(path: String?): PixelGrid {
    if (path == null) return PixelGrid(IntArray(0), GRID_COLS, GRID_ROWS)
    return withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(path).absolutePath, opts)
        var inSample = 1
        while (opts.outWidth / inSample > 128 || opts.outHeight / inSample > 128) inSample *= 2
        val opts2 = BitmapFactory.Options().apply { this.inSampleSize = inSample }
        val bitmap = BitmapFactory.decodeFile(File(path).absolutePath, opts2)
        try {
            samplePreviewGrid(bitmap)
        } finally {
            bitmap?.recycle()
        }
    }
}

/**
 * Mosaïque de la bulle : dessine chaque cellule à sa spot, celle du front courant
 * volant de son bord originel vers sa place. La bulle est un cercle (ou un angle
 * arrondi quand [circle] est faux) — le clip fait le reste.
 */
@Composable
internal fun PixelMosaic(
    grid: PixelGrid,
    fill: Float,
    circle: Boolean,
    modifier: Modifier = Modifier,
) {
    val route = remember { Animatable(0f) }
    // Progression du front : chaque cellule nait quand son temps est venu.
    LaunchedEffect(grid.ready) {
        if (grid.ready) route.animateTo(1f, tween(2_400, easing = LinearEasing))
        else route.snapTo(0f)
    }
    val colors = grid.colors
    Canvas(modifier) {
        if (colors.isEmpty() || fill <= 0f) return@Canvas
        val path = if (circle) {
            val r = size.minDimension / 2f
            Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(center.x - r, center.y - r, center.x + r, center.y + r))
            }
        } else {
            Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                        androidx.compose.ui.geometry.CornerRadius(26.dp.toPx()),
                    ),
                )
            }
        }
        clipPath(path) {
            val cols = grid.cols.coerceAtLeast(1)
            val rows = grid.rows.coerceAtLeast(1)
            val cellW = size.width / cols
            val cellH = size.height / rows
            var i = 0
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    if (i >= colors.size) break
                    drawCell(col, row, cols, cellW, cellH, colors[i], route.value)
                    i++
                }
            }
        }
    }
}

/** Une cellule : posée pleine une fois son vol fini, en vol sinon (décalage +
 * opacité par [pixelTrip]). Le front commence du bord supérieur-gauche vers le
 * centre, l'image vivante remplit les trous sous la mosaïque. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCell(
    col: Int,
    row: Int,
    cols: Int,
    cellW: Float,
    cellH: Float,
    color: Int,
    route: Float,
) {
    // Temps propre de la cellule : dispersé dans le front, le même arbitraire d'un
    // rendu à l'autre (pur : seed = index de cellule).
    val seed = row * cols + col
    val start = (seed % 17) / 24f
    val local = ((route - start) / 0.62f).coerceIn(0f, 1f)
    if (local <= 0f) return
    val xCell = col * cellW
    val yCell = row * cellH
    val trip = pixelTrip(
        fromX = xCell - cellW * 0.5f,
        fromY = yCell - cellH * 1.4f,
        toX = xCell,
        toY = yCell,
        t = local,
        seed = seed,
    )
    // En vol la cellule est réduite (elle tombe en place) ; une fois posée elle
    // occupe sa case exacte : la mosaïque ne laisse aucun trou une fois pleine.
    val scale = if (local >= 1f) 1f else trip.size.coerceAtLeast(0.35f)
    val w = cellW * scale
    val h = cellH * scale
    drawRect(
        color = Color(color),
        topLeft = androidx.compose.ui.geometry.Offset(trip.x + cellW * (1f - scale) / 2f, trip.y + cellH * (1f - scale) / 2f),
        size = androidx.compose.ui.geometry.Size(w, h),
    )
}
