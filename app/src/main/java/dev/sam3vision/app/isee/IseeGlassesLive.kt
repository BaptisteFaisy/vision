package dev.sam3vision.app.isee

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.SystemClock
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
import dev.sam3vision.app.glasses.textureViewCrop
import dev.sam3vision.app.targetSize
import dev.sam3vision.core.live.LinkDrop
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
            // Un essai par poussée, sans attendre : le flux bat à 15 i/s, la suivante
            // réessaie ~66 ms plus tard. Boucler ici affamerait le décodeur (constaté :
            // 200 entrées en 4 min au lieu de 15/s, première sortie à +4 min).
            // Le rendu est daté à ses bornes (même horloge monotone que
            // `arrivedAtNs`) : c'est l'étage `dec` de la mesure B2.
            GlassesVideoLink.frames.collect { box ->
                val frame = box?.frame ?: return@collect
                val decodeStartNs = SystemClock.elapsedRealtimeNanos()
                val rendered = decoder.render(frame)
                val decodeEndNs = SystemClock.elapsedRealtimeNanos()
                if (rendered) {
                    GlassesVideoLink.noteRendered(box.seq, frame, decodeStartNs, decodeEndNs)
                } else if (frame.compressed && !frame.codecConfig && decoder.hasSurface) {
                    // H.265 nominal, accueil à l'écoute, rien de sorti : décodeur muet.
                    GlassesVideoLink.markDrop(LinkDrop.DECODE_FAIL)
                }
            }
        }
    }
    DisposableEffect(decoder, attached) {
        GlassesVideoLink.attachSnapshot { attachedSnapshot(attached.view, attached) }
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
        update = { view -> size?.let { video -> applyCrop(view, attached, video) } },
        modifier = modifier,
    )
}

/** La `Surface` créée sur le `SurfaceTexture` : une seule vivante, toujours libérée. */
private class AttachedSurface {
    var current: Surface? = null
        private set

    /** La vue, pour photographier la rue vers la sonde SAM. */
    var view: TextureView? = null

    /**
     * Taille vidéo connue (0 = pas encore annoncée) : les buffers sont taillés
     * dessus, et toute surface recréée en plein direct la reprend aussitôt.
     */
    var videoW = 0
    var videoH = 0

    /** Taille des buffers déjà posée (0 = encore la taille initiale de la vue). */
    var bufferW = 0
    var bufferH = 0

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
            // Buffers à taille vidéo quand elle est déjà connue (surface recréée en
            // plein direct), sinon à taille de vue en attendant la première frame.
            texture.setDefaultBufferSize(
                attached.videoW.takeIf { it > 0 } ?: width,
                attached.videoH.takeIf { it > 0 } ?: height,
            )
            attached.set(Surface(texture))
            decoder.setSurface(attached.current)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            // La vue change de taille (rotation, multi-fenêtre) : les buffers
            // restent à taille vidéo quand elle est connue — c'est la matrice
            // « remplit la vue » qui absorbe le changement, en un seul upscale.
            texture.setDefaultBufferSize(
                attached.videoW.takeIf { it > 0 } ?: width,
                attached.videoH.takeIf { it > 0 } ?: height,
            )
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            decoder.setSurface(null)
            attached.release()
            // La prochaine texture repartira sur ces tailles (voir ci-dessus).
            attached.bufferW = 0
            attached.bufferH = 0
            return true
        }

        // Chaque image affichée ferme la mesure B2 (`aff` = pixel − sortie décodeur).
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            GlassesVideoLink.markPixel()
        }
    }

/** Recadre la vue sur la vidéo ; reporté après le layout si la vue n'a pas encore de taille. */
private fun applyCrop(view: TextureView, attached: AttachedSurface, video: VideoSize) {
    if (view.width <= 0 || view.height <= 0) {
        view.post { applyCrop(view, attached, video) }
        return
    }
    sizeBuffersToVideo(view, attached, video)
    view.setTransform(cropMatrix(video, view.width, view.height))
}

/**
 * Taille les buffers à la vidéo (décodage 1:1), une fois par résolution.
 *
 * Avant, les buffers faisaient la taille de la vue : le décodeur y réduisait
 * d'abord la vidéo (une 720×1280 tenait en ~337×600), puis la matrice
 * « remplit la vue » agrandissait ce résidu ~3,2× — double rééchantillonnage,
 * flou permanent. Ici le décodeur rend au pixel près et la matrice fait
 * l'unique upscale (GPU, filtré), comme tout lecteur `TextureView`.
 *
 * Le basculement a lieu à la première taille annoncée (première frame), quand
 * la rue est encore fantôme ou en fondu : un éventuel glitch transitoire du
 * redimensionnement y est masqué. À valider sur matériel : rue nette dès la
 * première seconde, pas de freeze au démarrage du direct.
 */
private fun sizeBuffersToVideo(view: TextureView, attached: AttachedSurface, video: VideoSize) {
    if (video.width <= 0 || video.height <= 0) return
    attached.videoW = video.width
    attached.videoH = video.height
    if (attached.bufferW == video.width && attached.bufferH == video.height) return
    val texture = view.surfaceTexture ?: return
    runCatching { texture.setDefaultBufferSize(video.width, video.height) }
        .onSuccess {
            attached.bufferW = video.width
            attached.bufferH = video.height
        }
}

/**
 * Photographie la rue à l'échelle d'envoi SAM (320 px, jamais agrandie) : c'est cette
 * image — exactement ce que l'utilisateur voit — qui part au guidage. `null` si la vue
 * n'a pas encore de taille. Note aussi la géométrie (vidéo + vue) pour le sténopé (D2).
 */
private fun attachedSnapshot(view: TextureView?, attached: AttachedSurface): android.graphics.Bitmap? {
    if (view == null || view.width <= 0 || view.height <= 0) return null
    if (attached.videoW > 0 && attached.videoH > 0) {
        GlassesVideoLink.noteSnapshotGeometry(attached.videoW, attached.videoH, view.width, view.height)
    }
    val (targetWidth, targetHeight) = targetSize(view.width, view.height, 0, FramePolicy.EDGE)
    return runCatching { view.getBitmap(targetWidth, targetHeight) }.getOrNull()
}

/**
 * Matrice « remplit la vue » : la vue étire déjà le buffer plein cadre, la matrice ne
 * fait que corriger l'étirement autour du centre (voir [textureViewCrop]) — pas un
 * placage échelle + décalage, qui déformait la rue.
 */
private fun cropMatrix(video: VideoSize, viewW: Int, viewH: Int): Matrix =
    Matrix().apply {
        val crop = textureViewCrop(video.width, video.height, viewW, viewH)
        setScale(crop.scaleX, crop.scaleY, viewW / 2f, viewH / 2f)
    }
