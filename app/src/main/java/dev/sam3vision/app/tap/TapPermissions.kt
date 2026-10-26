package dev.sam3vision.app.tap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.sam3vision.app.hasCameraPermission

/** Permissions du mode tap : caméra pour filmer, micro pour la piste audio de la vidéo. */
val TAP_PERMISSIONS: Array<String> = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/** Vrai si la permission micro est déjà accordée. */
fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Vrai si les deux permissions du mode sont accordées (sinon : annonce « Failed »). */
fun tapPermissionsGranted(context: Context): Boolean =
    hasCameraPermission(context) && hasAudioPermission(context)
