package dev.sam3vision.app.guidemap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.dp

/** Rayon des coins de la carte GPS, partagé avec le repli schéma. */
val GuideMapCornerRadius = 16.dp

/**
 * Vignettage « fondu » : les bords de la carte fondent dans le blanc de la
 * page (écho au fondu de la caméra). Quatre dégradés blancs, dessin seul :
 * aucun texte, aucune interception du toucher. Composé après les coins
 * (même règle que [GuideMapCorners] : le Compose d'avant passerait
 * derrière la carte).
 */
@Composable
internal fun GuideVignette(modifier: Modifier = Modifier) {
    val edge = 28.dp
    val opaque = Color.White
    val clear = Color.Transparent
    Box(modifier) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge).background(Brush.verticalGradient(listOf(opaque, clear))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge).background(Brush.verticalGradient(listOf(clear, opaque))))
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edge).background(Brush.horizontalGradient(listOf(opaque, clear))))
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edge).background(Brush.horizontalGradient(listOf(clear, opaque))))
    }
}

/**
 * Coins arrondis peints sur la carte : remplit le rectangle hors du
 * rectangle arrondi (remplissage pair-impair, bords adoucis), à la
 * couleur du fond derrière la carte. La surface GL ne se laissant pas
 * rogner par le `clip` Compose, c'est la seule retouche sans changer
 * le mode de rendu (texture = copie GPU par frame, écartée).
 */
@Composable
internal fun GuideMapCorners(cornerColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = GuideMapCornerRadius.toPx()
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
        }
        drawPath(outside, cornerColor)
    }
}
