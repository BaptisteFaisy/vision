package dev.sam3vision.app.segment

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pilote CameraX minimal de la démo segmentation : aperçu + analyse 640×360,
 * une frame à la fois (les anciennes sont jetées). Frère simplifié
 * d'`IseeCameraHost` (ni vidéo, ni JPEG : l'analyseur livre un bitmap redressé
 * au segmenteur). Tenu en paysage : le modèle est entraîné en 16:9.
 */
class SegmentCameraHost(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Bitmap redressé pile 640×360 : consommé par le segmenteur. */
    var onFrame: ((Bitmap) -> Unit)? = null

    private var frames = 0
    private var firstAt = 0L

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
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(
                        Size(SegmentPreprocess.INPUT_WIDTH, SegmentPreprocess.INPUT_HEIGHT),
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
            frames += 1
            if (frames == 1) {
                firstAt = SystemClock.elapsedRealtime()
                Log.i("sam3vision", "segment-cam: analyse ${proxy.width}x${proxy.height}")
            } else if (frames % 50 == 0) {
                val dt = SystemClock.elapsedRealtime() - firstAt
                Log.i("sam3vision", "segment-cam: $frames frames en ${dt}ms")
            }
            if (wantsFrame?.invoke() == false) return
            val bitmap = proxy.toBitmap() ?: return
            onFrame?.invoke(
                SegmentPreprocess.rotateAndFit(bitmap, proxy.imageInfo.rotationDegrees),
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
