package dev.sam3vision.app.isee

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.decodeScaledExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La photo des lunettes dans la rue (emplacement caméra) : elle remplace le
 * direct, sans fond noir ni bouton — elle reste affichée jusqu'au tap (la
 * caméra repart) ou à la photo suivante. Les questions posées pendant ce
 * temps l'ont en contexte (photo épinglée).
 *
 * Décodée hors du fil UI (un JPEG 12 Mpx prend ~200 ms) ; rotation : le
 * ViewModel garde la photo, seul le décodage rejoue. Fichier purgé entre-temps
 * (le worker horaire ne garde que le périmètre capture) : message au lieu d'un
 * cadre vide.
 */
@Composable
internal fun GlassesPhotoStreet(photo: IseeGlassesPhoto, modifier: Modifier = Modifier) {
    var bitmap by remember(photo) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(photo) { mutableStateOf(false) }
    LaunchedEffect(photo) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodeScaledExact(photo.file, GLASSES_PHOTO_EDGE) }.getOrNull()
        }
        bitmap = decoded
        failed = decoded == null
    }
    Box(modifier) {
        val image = bitmap
        if (image != null) {
            val picture = remember(image) { image.asImageBitmap() }
            Image(
                bitmap = picture,
                contentDescription = "Photo taken with the glasses",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else if (failed) {
            Text(
                "Glasses photo unreadable (file deleted?)",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC1A1F26), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Grand bord du décodage d'affichage : net sur un écran 1080×2400 sans garder
 * les 12 Mpx du capteur en mémoire (2 × [dev.sam3vision.app.MAX_IMAGE_EDGE]).
 */
internal const val GLASSES_PHOTO_EDGE = 2048