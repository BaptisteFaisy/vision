package dev.sam3vision.app.live

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.sam3vision.core.model.LiveFrame

/**
 * Dessin d'un masque (base64 + encodage) : colorié si le masque est lisible, `null` si le
 * backend n'en envoie pas (`"none"`) ou si le corps est corrompu — l'écran dessine alors
 * la boîte seule. C'est le point d'injection des tests : décoder un vrai PNG demande
 * [Bitmap], absent d'une JVM nue.
 */
fun interface MaskPainter<T> {
    fun paint(maskB64: String, encoding: String, argb: Int, alpha: Int): T?
}

/**
 * Masques colorisés d'une frame, **hors thread principal** : décoder un PNG gris et
 * recolorier 256 niveaux par objet coûtait 60-80 ms sur la boucle de composition, qui
 * tenait alors le rendu. Tout part donc sur [Dispatchers.Default], et [onDecoded] est
 * appelé juste après le coloriage — c'est le poste `overlays` de la chronologie, posé au
 * même instant qu'avant l'extraction.
 *
 * Le type [T] du dessin est un paramètre : [Bitmap] dans l'app, n'importe quoi de
 * comparable dans les tests JVM.
 */
class LiveMaskDecoder<T>(
    private val paint: MaskPainter<T>,
    private val colorAt: (index: Int) -> Int,
    private val alpha: Int,
    private val onDecoded: () -> Unit = {},
) {

    /** Overlays de la frame, dans l'ordre des objets ; les masques illisibles sont omis. */
    suspend fun decode(frame: LiveFrame): List<T> = withContext(Dispatchers.Default) {
        val drawn = frame.objects.mapIndexedNotNull { index, obj ->
            paint.paint(obj.maskPngB64, frame.maskEncoding, colorAt(index), alpha)
        }
        onDecoded()
        drawn
    }
}