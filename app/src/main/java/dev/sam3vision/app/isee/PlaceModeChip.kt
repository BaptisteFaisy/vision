package dev.sam3vision.app.isee

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.picto.PictoPlaceMode

/** Bleu « dehors » : la rue parle (même teinte que le bleu écoute). */
const val PLACE_MODE_ACCENT_OUTDOOR = 0xFF2E79FF.toInt()

/** Violet « dedans » : les pictos sont suivis et guidés. */
const val PLACE_MODE_ACCENT_INDOOR = 0xFF9133E8.toInt()

/**
 * Libellé du témoin de lieu : le mode courant, avec les mots des ordres
 * vocaux (« mode indoor/outdoor »). Pur, testé en JVM.
 */
internal fun placeModeChipLabel(mode: PictoPlaceMode): String = when (mode) {
    PictoPlaceMode.DEHORS -> "Mode: OUTDOOR"
    PictoPlaceMode.DEDANS -> "Mode: INDOOR"
}

/**
 * Pastille du témoin de lieu : bleu dehors, violet dedans — hors palette
 * Traversée (gris/rouge/vert/ambre), les deux puces restent lisibles côte
 * à côte. Pur, testé en JVM.
 */
fun placeModeChipAccent(mode: PictoPlaceMode): Int = when (mode) {
    PictoPlaceMode.DEHORS -> PLACE_MODE_ACCENT_OUTDOOR
    PictoPlaceMode.DEDANS -> PLACE_MODE_ACCENT_INDOOR
}

/**
 * Zone tactile publiée par la puce lieu (coordonnées fenêtre, `null` quand
 * masquée) : la racine y exempte ses gestes, comme pour les chips Picto et
 * Traversée.
 */
class PlaceModeChipZone {
    private var bounds: RectF? = null

    /** Zone publiée, `null` quand la puce n'est pas affichée. */
    fun zone(): RectF? = bounds

    /** La puce se place / se retire : la racine exempte sa zone de l'interception. */
    fun setBounds(bounds: RectF?) {
        this.bounds = bounds
    }
}

/**
 * Témoin du lieu (dedans/dehors) : pastille d'état et libellé, tap = bascule
 * par la même phrase canonique que l'ordre vocal. Même gabarit que
 * [CrossingChip] (fond, pastille 7 dp, texte 10 sp gras) : les deux puces
 * forment une seule rangée de témoins en haut de la rue Portrait.
 */
@Composable
internal fun BoxScope.PlaceModeChip(
    mode: PictoPlaceMode,
    modifier: Modifier,
    onToggle: () -> Unit,
    onBounds: (RectF?) -> Unit,
) {
    val dot = Color(placeModeChipAccent(mode))
    // La racine intercepte tous les taps : la puce publie sa zone fenêtre pour
    // en être exemptée, et la retire en partant (sinon taps fantômes).
    DisposableEffect(Unit) { onDispose { onBounds(null) } }
    Row(
        modifier
            .onGloballyPositioned { coords ->
                val pos = coords.localToWindow(Offset.Zero)
                val size = coords.size
                onBounds(RectF(pos.x, pos.y, pos.x + size.width, pos.y + size.height))
            }
            .padding(8.dp)
            .background(Color(0x99000000), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.size(5.dp))
        Text(
            placeModeChipLabel(mode),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
