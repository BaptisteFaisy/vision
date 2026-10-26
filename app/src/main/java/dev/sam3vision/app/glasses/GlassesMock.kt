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
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import java.io.FileOutputStream

/**
 * Lunettes simulées (Mock Device Kit) : démo complète sans matériel, et filet de
 * sécurité le jour J si l'appairage ou la mise à jour firmware coince.
 */
internal class GlassesMock(private val context: Context) {

    private var glasses: MockGlasses? = null

    var active: Boolean = false
        private set

    fun start(): Boolean {
        val kit = MockDeviceKit.getInstance(context)
        if (!kit.isEnabled) {
            kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
        }
        kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
        val paired = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrNull() ?: return false
        feedScene(paired)
        glasses = paired
        active = true
        return true
    }

    /** Injecte un appui sur le bouton capture, comme le ferait un vrai porteur. */
    fun press(type: CapturePressType = CapturePressType.SHORT_PRESS): Boolean {
        val device = glasses ?: return false
        device.services.input.capture(type)
        return true
    }

    fun stop() {
        val device = glasses ?: return
        MockDeviceKit.getInstance(context).unpairDevice(device)
        glasses = null
        active = false
    }

    /** Scène synthétique : des formes franches, la segmentation a de quoi travailler. */
    private fun feedScene(device: MockGlasses) {
        val uri = Uri.fromFile(sceneFile())
        device.services.camera.setCameraFeed(uri)
        device.services.cameraCapture.setCapturedPhoto(uri)
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
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}