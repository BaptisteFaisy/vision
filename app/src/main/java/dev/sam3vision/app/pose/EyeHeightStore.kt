package dev.sam3vision.app.pose

import android.content.Context
import dev.sam3vision.core.distance.CameraPose

/**
 * Hauteur lue bornée main ([CameraPose.MIN..MAX]), [default] si illisible : une
 * seule lecture pour les deux stores ([CameraHeightStore], [EyeHeightStore]).
 * Pur, testé en JVM via [clampEyeHeight].
 */
internal fun clampHeightPref(raw: Float, default: Float): Float =
    if (!raw.isFinite()) default else raw.coerceIn(CameraPose.MIN_HEIGHT_M, CameraPose.MAX_HEIGHT_M)

/**
 * Hauteur des yeux (m) bornée main, défaut yeux 1,6 m : le pur testé en JVM
 * (les stores à `Context` n'ont pas de test JVM).
 */
internal fun clampEyeHeight(raw: Float): Float = clampHeightPref(raw, CameraPose.GLASSES_HEIGHT_M)

/**
 * Hauteur des yeux (m), préférence d'app lue à chaque échantillon Motion : un
 * réglage changé dans `DistanceSettings` s'applique au tangage suivant, sans
 * redémarrer la session lunettes. Miroir d'[CameraHeightStore] (mêmes bornes
 * main, défaut [CameraPose.GLASSES_HEIGHT_M]) : aucune valeur n'a changé, seule
 * la hauteur figée devient réglable.
 */
class EyeHeightStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Hauteur tenue, bornée main, défaut yeux 1,6 m. */
    fun get(): Float = clampEyeHeight(prefs.getFloat(KEY, CameraPose.GLASSES_HEIGHT_M))

    /** Mémorise la hauteur (bornée main, défaut yeux si illisible). */
    fun set(meters: Float) {
        prefs.edit().putFloat(KEY, clampEyeHeight(meters)).apply()
    }

    private companion object {
        const val PREFS = "pose"
        const val KEY = "eyeHeightM"
    }
}
