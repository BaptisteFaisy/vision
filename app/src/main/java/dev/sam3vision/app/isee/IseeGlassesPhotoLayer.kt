package dev.sam3vision.app.isee

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sam3vision.app.decodeScaledExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La photo des lunettes sur l'écran de la personne accompagnée : plein cadre,
 * nette ([GLASSES_PHOTO_EDGE] sur le grand bord), sur fond noir.
 *
 * Une `Dialog` plein écran, pas un calque : la racine [dev.sam3vision.app.IseeRootLayout]
 * intercepte tous les gestes quand iSee est actif (un bouton dans la scène ne
 * recevrait aucun tap — seul AlertDialog/zone exemptée y échappe, voir
 * [PictoDialog]). Le retour système et le bouton ferment la photo sans quitter
 * iSee ; aucun tap ne traverse vers la vidéo.
 *
 * Décodée hors du fil UI (un JPEG 12 Mpx prend ~200 ms) ; rotation : le
 * ViewModel garde la photo, seul le décodage rejoue. Fichier purgé entre-temps
 * (le worker horaire ne garde que le périmètre capture) : message au lieu d'un
 * cadre vide.
 */
@Composable
internal fun GlassesPhotoLayer(photo: IseeGlassesPhoto, onDismiss: () -> Unit) {
    var bitmap by remember(photo) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(photo) { mutableStateOf(false) }
    LaunchedEffect(photo) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodeScaledExact(photo.file, GLASSES_PHOTO_EDGE) }.getOrNull()
        }
        bitmap = decoded
        failed = decoded == null
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val image = bitmap
            if (image != null) {
                val picture = remember(image) { image.asImageBitmap() }
                Image(
                    bitmap = picture,
                    contentDescription = "Photo prise avec les lunettes",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (failed) {
                Text(
                    "Photo des lunettes illisible (fichier effacé ?)",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            ) {
                Text("Fermer", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

/**
 * Grand bord du décodage d'affichage : net sur un écran 1080×2400 sans garder
 * les 12 Mpx du capteur en mémoire (2 × [dev.sam3vision.app.MAX_IMAGE_EDGE]).
 */
internal const val GLASSES_PHOTO_EDGE = 2048
