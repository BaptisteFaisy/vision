package dev.sam3vision.app.isee

import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.isee.vm.PictoPhase
import dev.sam3vision.app.isee.vm.PictoUiState
import dev.sam3vision.core.picto.PictoHit

/** Le jaune des pictogrammes : cadre et étiquette reprennent leur palette. */
private val PICTO_YELLOW = Color(0xFFFFD600)

/**
 * Les objets reconnus, dessinés sur la vidéo : le pictogramme remplit le
 * rectangle reconnu, son nom en étiquette au-dessus (dedans si trop haut).
 * Posée après les pastilles de distance, comme [SteeringLayer]. Rien de
 * composé sans reconnaissance — la vidéo reste nette le reste du temps.
 */
@Composable
internal fun PictoLayer(hits: List<PictoHit>, pngOf: (String) -> ByteArray?, modifier: Modifier = Modifier) {
    if (hits.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fullW = maxWidth
        val fullH = maxHeight
        hits.forEach { hit ->
            key(hit.pictoId) {
                val image = remember(hit.pictoId) {
                    runCatching {
                        pngOf(hit.pictoId)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }.getOrNull()?.asImageBitmap()
                }
                // `?.let`, jamais `?: return@key` : le retour étiqueté émet une
                // classe synthétique que D8 refuse (« NON_LOCAL_RETURN », vu au dex).
                image?.let { bitmap ->
                    val left = fullW * hit.x
                    val top = fullH * hit.y
                    Box(
                        Modifier
                            .offset(left, top)
                            .size(fullW * hit.w, fullH * hit.h)
                            .border(2.dp, PICTO_YELLOW, RoundedCornerShape(8.dp)),
                    ) {
                        Image(bitmap, contentDescription = hit.label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Text(
                        hit.label,
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .offset(left, (fullH * hit.y - 22.dp).coerceAtLeast(0.dp))
                            .background(PICTO_YELLOW, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Bouton « Picto » de la rue : pastille jaune quand des repères sont armés,
 * grise sinon, et « … » pendant la photo ou la génération. Posé en bas de rue,
 * à côté du debug — même gabarit que les chips de la carte historique.
 */
@Composable
internal fun BoxScope.PictoChip(
    picto: PictoUiState,
    modifier: Modifier,
    onPicto: () -> Unit,
    onBounds: (RectF?) -> Unit,
) {
    val dot = if (picto.armed > 0) PICTO_YELLOW else Color(0xFF808080)
    // La racine intercepte tous les taps : le chip publie sa zone fenêtre pour
    // en être exempté, et la retire en partant (sinon taps fantômes).
    DisposableEffect(Unit) { onDispose { onBounds(null) } }
    Row(
        modifier
            .onGloballyPositioned { coords ->
                // Compose 1.11 : ni boundsInWindow ni positionInWindow n'existent
                // plus — localToWindow(Zero) + size (prouvé au javap du cache).
                val pos = coords.localToWindow(Offset.Zero)
                val size = coords.size
                onBounds(RectF(pos.x, pos.y, pos.x + size.width, pos.y + size.height))
            }
            .padding(8.dp)
            .background(Color(0x99000000), RoundedCornerShape(8.dp))
            .clickable(onClick = onPicto)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.size(5.dp))
        Text(
            pictoChipLabel(picto),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Libellé du bouton : l'attente, les armés, ou l'appel à créer le premier. */
internal fun pictoChipLabel(picto: PictoUiState): String = when {
    picto.phase == PictoPhase.Shooting || picto.phase == PictoPhase.Generating -> "Picto…"
    picto.armed > 0 -> "Picto : ${picto.armed}"
    else -> "Picto"
}
