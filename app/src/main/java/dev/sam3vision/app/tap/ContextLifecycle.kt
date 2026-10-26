package dev.sam3vision.app.tap

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.LifecycleOwner

/**
 * Retrouve le [LifecycleOwner] derrière un `Context` d'Activity, même enveloppé dans un
 * `ContextThemeWrapper` (thème Compose). Utilisé pour lier `VideoCapture` au cycle de vie
 * de l'écran sans dépendre de `LocalLifecycleOwner` (déplacé dans un autre artefact).
 */
fun Context.findLifecycleOwner(): LifecycleOwner? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<LifecycleOwner>()
        .firstOrNull()
