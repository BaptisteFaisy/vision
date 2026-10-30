package dev.sam3vision.app.isee

import android.graphics.RectF
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned

/** Bouton « Arrêter le guidage » du bloc guidage sur l'accueil. */
internal const val NAV_STOP_ID = "guidance-stop"

/** Bouton « Démarrer » du bloc confirmation sur l'accueil. */
internal const val CONFIRM_START_ID = "confirm-start"

/** Bouton « Annuler » du bloc confirmation sur l'accueil. */
internal const val CONFIRM_CANCEL_ID = "confirm-cancel"

/** Saisie clavier de secours du bloc confirmation sur l'accueil. */
internal const val CONFIRM_TEXT_ID = "confirm-text"

/**
 * Zones tactiles des boutons navigation de l'accueil (coordonnées fenêtre) :
 * la racine y exempte ses gestes, comme pour les chips Picto et Traversée.
 * Une entrée par bouton (clés stables ci-dessus), retirée au départ du bouton.
 */
class NavButtonZones {
    private val bounds = mutableMapOf<String, RectF>()

    /** Zones publiées (vide quand aucun bouton navigation n'est affiché). */
    fun zones(): List<RectF> = bounds.values.toList()

    /** Le bouton [id] se place / se retire : la racine exempte sa zone. */
    fun setBounds(id: String, bounds: RectF?) {
        if (bounds == null) this.bounds.remove(id) else this.bounds[id] = bounds
    }
}

/**
 * Publie la zone fenêtre du bouton [id] dans [zones] (exemption racine),
 * retirée à la sortie de composition (sinon taps fantômes).
 */
fun Modifier.navZone(id: String, zones: NavButtonZones): Modifier = composed {
    DisposableEffect(id, zones) { onDispose { zones.setBounds(id, null) } }
    onGloballyPositioned { coords ->
        val pos = coords.localToWindow(Offset.Zero)
        val size = coords.size
        zones.setBounds(id, RectF(pos.x, pos.y, pos.x + size.width, pos.y + size.height))
    }
}
