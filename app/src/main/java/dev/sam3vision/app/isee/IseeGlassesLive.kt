package dev.sam3vision.app.isee

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.sam3vision.app.glasses.GlassesLiveDecoder
import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.glasses.VideoSize
import dev.sam3vision.app.glasses.cropTransform
import dev.sam3vision.app.targetSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Le direct des lunettes dans la rue du Portrait : un `TextureView` dont la surface est
 * nourrie par le décodeur, recadré « remplit la vue ». Même place, même fondu, même boule
 * dessous que la caméra du téléphone — seule la source change.
 *
 * Le décodage tourne hors du fil UI (les frames arrivent à 15 i/s) et ne recompose rien :
 * seule la taille vidéo recadre la vue, une fois par résolution.
 */
@Composable
internal fun GlassesLive(modifier: Modifier) {
    val decoder = remember { GlassesLiveDecoder(GlassesVideoLink::onVideoSize, GlassesVideoLink::note) }
    val attached = remember { AttachedSurface() }
    val size by GlassesVideoLink.videoSize.collectAsState()
    LaunchedEffect(decoder) {
        launch(Dispatchers.Default) {
            // Un essai par poussée, sans attendre : le flux bat à 30 i/s, la suivante
            // réessaie 33 ms plus tard. Boucler ici affamerait le décodeur (constaté :
            // 200 entrées en 4 min au lieu de 30/s, première sortie à +4 min).
            GlassesVideoLink.frames.collect { box ->
                val frame = box?.frame ?: return@collect
                if (decoder.render(frame)) GlassesVideoLink.markRendered()
            }
        }
    }
    DisposableEffect(decoder, attached) {
        GlassesVideoLink.attachSnapshot { attachedSnapshot(attached.view) }
        onDispose {
            GlassesVideoLink.detachSnapshot()
            decoder.release()
            attached.release()
        }
    }
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = glassesListener(decoder, attached)
                attached.view = this
            }
        },
        update = { view -> size?.let { video -> applyCrop(view, video) } },
        modifier = modifier,
    )
}

/** La `Surface` créée sur le `SurfaceTexture` : une seule vivante, toujours libérée. */
private class AttachedSurface {
    var current: Surface? = null
        private set

    /** La vue, pour photographier la rue vers la sonde SAM. */
    var view: TextureView? = null

    fun set(surface: Surface) {
        release()
        current = surface
    }

    fun release() {
        runCatching { current?.release() }
        current = null
    }
}

/** Branche la surface au décodeur quand la texture naît, la détache quand elle meurt. */
private fun glassesListener(decoder: GlassesLiveDecoder, attached: AttachedSurface) =
    object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            texture.setDefaultBufferSize(width, height)
            attached.set(Surface(texture))
            decoder.setSurface(attached.current)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            texture.setDefaultBufferSize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            decoder.setSurface(null)
            attached.release()
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

/** Recadre la vue sur la vidéo ; reporté après le layout si la vue n'a pas encore de taille. */
private fun applyCrop(view: TextureView, video: VideoSize) {
    if (view.width <= 0 || view.height <= 0) {
        view.post { applyCrop(view, video) }
        return
    }
    view.setTransform(cropMatrix(video, view.width, view.height))
}

/**
 * Photographie la rue à l'échelle d'envoi SAM (320 px, jamais agrandie) : c'est cette
 * image — exactement ce que l'utilisateur voit — qui part au guidage. `null` si la vue
 * n'a pas encore de taille.
 */
private fun attachedSnapshot(view: TextureView?): android.graphics.Bitmap? {
    if (view == null || view.width <= 0 || view.height <= 0) return null
    val (targetWidth, targetHeight) = targetSize(view.width, view.height, 0, FramePolicy.EDGE)
    return runCatching { view.getBitmap(targetWidth, targetHeight) }.getOrNull()
}

/** Matrice « remplit la vue » depuis le recadrage pur (même géométrie que le dessin). */
private fun cropMatrix(video: VideoSize, viewW: Int, viewH: Int): Matrix =
    Matrix().apply {
        val crop = cropTransform(video.width, video.height, viewW, viewH)
        setScale(crop.scale, crop.scale)
        postTranslate(crop.dx, crop.dy)
    }
