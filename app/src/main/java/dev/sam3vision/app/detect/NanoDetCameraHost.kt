package dev.sam3vision.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import dev.sam3vision.app.previewResolutionSelector
import dev.sam3vision.app.uprightScaled
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pilote CameraX minimal de la démo NanoDet : aperçu + analyse 320, une frame
 * à la fois (les anciennes sont jetées). Frère simplifié d'`IseeCameraHost`
 * (ni vidéo, ni JPEG : l'analyseur livre un bitmap redressé au détecteur).
 */
class NanoDetCameraHost(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Bitmap redressé, bord long 320 : consommé par le détecteur. */
    var onFrame: ((Bitmap) -> Unit)? = null

    /** Faux = une inférence est déjà en vol : on jette la frame sans convertir. */
    var wantsFrame: (() -> Boolean)? = null

    fun newPreviewView(context: Context): PreviewView =
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }

    /** Lie aperçu + analyse au cycle de vie ; `null` = OK, sinon raison affichable. */
    suspend fun bind(owner: LifecycleOwner, view: PreviewView): String? {
        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        val preview = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector())
            .build()
            .also { it.setSurfaceProvider(view.surfaceProvider) }
        val edge = NanoDetPreprocess.INPUT_SIZE
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(
                        Size(edge, edge),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                ).build(),
            )
            .build()
            .also { it.setAnalyzer(executor, ::analyse) }
        return runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            null
        }.getOrElse { "Camera binding failed: ${it.message}" }
    }

    /** Délie tout (sortie d'écran) ; à appeler hors du fil principal. */
    fun unbind() {
        runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
    }

    private fun analyse(proxy: ImageProxy) {
        try {
            if (wantsFrame?.invoke() == false) return
            val bitmap = proxy.toBitmap() ?: return
            onFrame?.invoke(
                uprightScaled(
                    bitmap,
                    proxy.imageInfo.rotationDegrees,
                    NanoDetPreprocess.INPUT_SIZE,
                ),
            )
        } catch (_: Throwable) {
            // frame illisible : la suivante arrive tout de suite
        } finally {
            proxy.close()
        }
    }

    /** Libère l'executor (la caméra est déliée par [unbind]). */
    fun release() {
        executor.shutdown()
    }
}
