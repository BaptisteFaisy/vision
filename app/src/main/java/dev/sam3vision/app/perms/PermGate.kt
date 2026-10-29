package dev.sam3vision.app.perms

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Vrai si [perm] est accordée à l'app. */
fun hasPerm(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
