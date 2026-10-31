package dev.sam3vision.app.isee

import android.graphics.Bitmap
import dev.sam3vision.core.live.LiveTimings
import dev.sam3vision.core.live.ScreenTimings
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Masques colorisés de la frame, calculés hors du thread principal : décodage selon
 * l'encodage **de l'objet** (`rle`/`poly` sur la grille réduite, `bits` = 1 bit + zlib,
 * sinon PNG gris) puis coloriage par table de 256 niveaux — jamais de luminance sur un masque déjà gris.
 *
 * Le coût est mesuré (une ligne tous les [LOG_EVERY_FRAMES] paquets) : c'est du temps
 * mural entre la réponse réseau et ce qui arrive à l'écran, et il n'est pas dans `latence`.
 *
 * Extraite de [LiveProbe] (plafond 500 lignes) : dépendances en paramètres,
 * comportement inchangé.
 */
internal suspend fun overlaysFor(
    frame: LiveFrame,
    shown: LiveTimings,
    background: CoroutineDispatcher,
    painter: MaskPainter,
    chronology: ScreenTimings,
): List<Bitmap> = withContext(background) {
    val startedAt = android.os.SystemClock.elapsedRealtime()
    val overlays = painter.overlays(frame)
    chronology.markOverlays(shown)
    painter.noteDecoded(android.os.SystemClock.elapsedRealtime() - startedAt, overlays.size)
    overlays
}
