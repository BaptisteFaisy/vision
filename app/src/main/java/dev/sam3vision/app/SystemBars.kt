package dev.sam3vision.app

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Écrans clairs (fond blanc) : le bandeau système du haut (heure, batterie)
 * y est blanc, aux icônes sombres. La scène iSee cache la barre ; les écrans
 * sombres et caméra gardent leurs icônes claires sur fond sombre.
 */
internal fun whiteStatusBar(route: Route): Boolean = when (route) {
    Route.Settings, Route.Caregiver, Route.Onboarding -> true
    Route.Isee, Route.Capture, Route.Analysis, Route.Chat, Route.Live,
    Route.Bench, Route.VoiceNav, is Route.Tap -> false
}

/**
 * Écrans clairs : le bandeau système du haut est blanc, aux icônes sombres.
 * Posé après le contenu (et les overlays) pour rester blanc partout.
 */
@Composable
internal fun BoxScope.WhiteTopBar(route: Route) {
    if (!whiteStatusBar(route)) return
    WhiteStatusBarEffect()
    TopScrim()
}

/**
 * Bandeau blanc derrière la barre système du haut, sur les écrans clairs
 * ([whiteStatusBar]) : posé explicitement, sans dépendre du fond de chaque
 * écran. Nul quand la barre est cachée (scène iSee).
 */
@Composable
internal fun BoxScope.TopScrim() {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (top > 0.dp) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top).background(Color.White),
        )
    }
}

/**
 * Icônes système sombres sur les écrans clairs, restaurées en sortie (même
 * forme que la scène iSee : état précédent sauvé puis reposé).
 */
@Composable
internal fun WhiteStatusBarEffect() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = true
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
}
