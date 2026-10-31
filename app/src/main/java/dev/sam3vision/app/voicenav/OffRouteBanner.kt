package dev.sam3vision.app.voicenav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.StatusBanner

/** Texte du bandeau d'écart, affiché pendant le recalcul qui suit un écart. */
const val OFF_ROUTE_BANNER_TEXT = "Off route — recalculating…"

/**
 * Texte du bandeau selon l'état d'écart : [OFF_ROUTE_BANNER_TEXT] si écart,
 * `null` sinon (bandeau masqué). Pur JVM : testé sans Compose.
 */
fun offRouteBannerText(offRoute: Boolean): String? =
    if (offRoute) OFF_ROUTE_BANNER_TEXT else null

/**
 * Bandeau « Off route — recalculating… » pendant l'écart de route, rien sinon.
 *
 * Réutilise [StatusBanner] (couleur warning) : un seul bandeau d'état dans
 * l'app, pas un style par écran. Alimenté par `GuidanceUpdate.offRoute`
 * (contrat renseigné par le driver, lu par [GuidanceScreen]).
 */
@Composable
fun OffRouteBanner(offRoute: Boolean, modifier: Modifier = Modifier) {
    val text = offRouteBannerText(offRoute) ?: return
    StatusBanner(message = text, color = AppColors.warning, modifier = modifier)
}
