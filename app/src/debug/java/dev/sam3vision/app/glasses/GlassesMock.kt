package dev.sam3vision.app.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.inputs.types.CapturePressType
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import dev.sam3vision.app.glasses.bench.MockGlassesHarness
import java.io.File
import java.io.FileOutputStream

/**
 * Lunettes simulées (Mock Device Kit) : démo complète sans matériel, et filet de
 * sécurité le jour J si l'appairage ou la mise à jour firmware coince.
 *
 * Vit dans `app/src/debug/` : la bibliothèque `mwdat-mockdevice` est en
 * `debugImplementation` (`app/build.gradle.kts`) et n'est donc pas dans l'APK public.
 */
internal class GlassesMock(private val context: Context) : GlassesMockDevice {

    private var glasses: MockGlasses? = null

    override fun start(): Boolean {
        val appContext = context.applicationContext
        val kit = MockDeviceKit.getInstance(appContext)
        if (!kit.isEnabled) {
            kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
        }
        kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
        // Le harnais du banc : réutilise la paire déjà appariée (un run précédent en laisse
        // une, et un second appariement direct est refusé), puis allume, porte et déplie —
        // le mock refuse de streamer sans ça. Sans ces deux étapes, le direct restait noir.
        if (!MockGlassesHarness.enable(appContext, ::logLine)) return false
        val paired = kit.pairedDevices.filterIsInstance<MockGlasses>().firstOrNull()
        if (paired == null) {
            Log.w(TAG, "mock lunettes : harnais prêt mais aucune paire simulée visible")
            return false
        }
        feedScene(paired)
        glasses = paired
        return true
    }

    private fun logLine(line: String) = Log.i(TAG, "mock lunettes : $line")

    /** Injecte un appui sur le bouton capture, comme le ferait un vrai porteur. */
    override fun press(type: CapturePressType): Boolean {
        val device = glasses ?: return false
        device.services.input.capture(type)
        return true
    }

    override fun stop() {
        val device = glasses ?: return
        MockDeviceKit.getInstance(context).unpairDevice(device)
        glasses = null
    }

    /**
     * Le flux simulé exige une vraie vidéo (le banc le prouve avec `mock_feed.mp4`) : nourri
     * d'une image fixe, le streamer meurt en erreur critique. La photo capturée reste la
     * scène synthétique (formes franches, la segmentation a de quoi travailler).
     */
    private fun feedScene(device: MockGlasses) {
        val feed = runCatching { videoFile() }.getOrNull()
        if (feed == null) Log.w(TAG, "mock lunettes : vidéo $VIDEO_ASSET introuvable, repli image fixe")
        device.services.camera.setCameraFeed(Uri.fromFile(feed ?: sceneFile()))
        device.services.cameraCapture.setCapturedPhoto(Uri.fromFile(sceneFile()))
        if (feed != null) {
            val csd = runCatching { extractCsd(feed) }.getOrNull()
            GlassesVideoLink.fallbackCsd = csd
            Log.i(TAG, "mock lunettes : csd-0 ${csd?.size ?: 0} o extrait de $VIDEO_ASSET")
        }
    }

    /**
     * Paramètres H.265 de la vidéo (boîte hvcC du mp4) : le flux simulé n'envoie que des
     * tranches (constaté : NAL 0/1, jamais 32/33/34), le décodeur les prend en repli.
     */
    private fun extractCsd(video: File): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_VIDEO_HEVC) continue
                val csd = format.getByteBuffer("csd-0") ?: return null
                return ByteArray(csd.remaining()).also { csd.get(it) }
            }
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** La vidéo h.265 des assets debug, recopiée dans le cache (comme le banc). */
    private fun videoFile(): File {
        val file = File(context.cacheDir, VIDEO_ASSET)
        if (file.exists()) return file
        context.assets.open(VIDEO_ASSET).use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun sceneFile(): File {
        val file = File(context.cacheDir, "mock-scene.jpg")
        if (file.exists()) return file
        FileOutputStream(file).use { out -> scene().compress(Bitmap.CompressFormat.JPEG, 90, out) }
        return file
    }

    private fun scene(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(238, 236, 230))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(196, 72, 56)
        canvas.drawCircle(200f, 190f, 90f, paint)
        paint.color = Color.rgb(44, 108, 176)
        canvas.drawRect(380f, 120f, 590f, 300f, paint)
        paint.color = Color.rgb(72, 150, 96)
        canvas.drawRect(140f, 360f, 520f, 430f, paint)
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawCircle(560f, 400f, 45f, paint)
        return bitmap
    }

    private companion object {
        const val TAG = "GlassesMock"
        const val VIDEO_ASSET = "mock_feed.mp4"
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}