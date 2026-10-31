package dev.sam3vision.app.pose

import android.content.Context
import dev.sam3vision.core.distance.CameraPose

/**
 * Hauteur de la caméra (m), préférence d'app lue à chaque échantillon IMU : un
 * réglage changé dans [DistanceSettings] s'applique au tangage suivant, sans
 * redémarrer le direct.
 *
 * Vit ici, dans `:app`, et pas dans `Settings` (`:core`, gelé sur cette branche) :
 * le contournement est documenté dans `docs/POSE-IMU.md`, en attendant `cameraHeightM`
 * dans les réglages persistés.
 */
class CameraHeightStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Hauteur tenue, bornée main ([CameraPose.MIN..MAX]), défaut épaule 1,5 m. */
    fun get(): Float {
        val raw = prefs.getFloat(KEY, CameraPose.DEFAULT_HEIGHT_M)
        if (!raw.isFinite()) return CameraPose.DEFAULT_HEIGHT_M
        return raw.coerceIn(CameraPose.MIN_HEIGHT_M, CameraPose.MAX_HEIGHT_M)
    }

    /** Mémorise la hauteur (bornée main, défaut épaule si illisible). */
    fun set(meters: Float) {
        val clamped = if (meters.isFinite()) {
            meters.coerceIn(CameraPose.MIN_HEIGHT_M, CameraPose.MAX_HEIGHT_M)
        } else {
            CameraPose.DEFAULT_HEIGHT_M
        }
        prefs.edit().putFloat(KEY, clamped).apply()
    }

    private companion object {
        const val PREFS = "pose"
        const val KEY = "cameraHeightM"
    }
}
