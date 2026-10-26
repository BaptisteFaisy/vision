package dev.sam3vision.app.vm

import dev.sam3vision.app.MAX_IMAGE_EDGE
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Photo prête à l'envoi : l'image à afficher et son JPEG base64. */
data class LoadedPhoto<T>(val image: T, val base64: String)

/**
 * Chargement d'une photo capturée ou choisie : décodage **réduit au bord max**
 * ([maxEdge], 1024 px par défaut, jamais d'agrandissement), puis encodage JPEG base64.
 *
 * [decode] et [encode] sont les seules implémentations de l'app (`decodeScaledExact`,
 * `bitmapToJpegBase64`) : rien n'est réécrit ici, et la classe se teste en JVM sans
 * `Bitmap`. Les deux étapes lourdes restent hors du thread principal, comme avant
 * (IO pour le fichier, Default pour l'encodage).
 */
class AppImageLoader<T>(
    private val decode: (File, Int) -> T?,
    private val encode: (T) -> String,
    private val maxEdge: Int = MAX_IMAGE_EDGE,
) {

    /** Photo décodée et encodée, `null` si le fichier n'est pas une image lisible. */
    suspend fun load(file: File): LoadedPhoto<T>? {
        val image = withContext(Dispatchers.IO) { decode(file, maxEdge) } ?: return null
        val base64 = withContext(Dispatchers.Default) { encode(image) }
        return LoadedPhoto(image = image, base64 = base64)
    }
}