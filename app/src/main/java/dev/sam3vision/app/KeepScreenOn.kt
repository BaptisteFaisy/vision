package dev.sam3vision.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Routes qui gardent l'écran allumé : le seul accueil iSee (chat plein écran).
 * L'utilisateur écoute les annonces sans toucher le téléphone — la mise en
 * veille couperait l'usage en pleine rue. Les autres écrans (réglages, banc,
 * direct classique...) gardent le comportement système normal.
 */
internal fun keepsScreenOn(route: Route): Boolean = route == Route.Isee

/**
 * Maintient l'écran allumé tant que [enabled] est vrai : pose `keepScreenOn`
 * sur la vue racine, retiré à la sortie (changement de route, rotation).
 * Le flag ne vaut que fenêtre visible — aucun effet en arrière-plan.
 */
@Composable
internal fun KeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        if (enabled) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
