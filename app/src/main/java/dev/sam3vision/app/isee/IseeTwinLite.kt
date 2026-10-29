package dev.sam3vision.app.isee

import android.app.Application
import dev.sam3vision.app.speech.AndroidTtsDriver
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * TwinLiteNet de l'écran iSee, hors du ViewModel (limite 500 lignes) : moteur
 * voix système + câblage flèche/voix, même durée de vie que l'écran.
 */
class IseeTwinLite(app: Application, scope: CoroutineScope, settings: () -> Settings) {
    private val engine = SystemTtsEngine(AndroidTtsDriver(app))
    private val wiring = TwinLiteWiring(
        scope,
        app,
        TwinLiteVoice(engine),
        language = { settings().speechLanguage },
        rate = { settings().ttsSpeed },
    )

    /** Overlay courant (`null` = caché : pas de masque frais). */
    val ui: StateFlow<TwinLiteUi?> = wiring.ui

    /** Frame à segmenter (même JPEG que SAM). */
    fun offerFrame(jpegB64: String) = wiring.offerFrame(jpegB64)

    /** Nouvelle session : masque, annonceur et overlay oubliés (moteur gardé chaud). */
    fun reset() = wiring.reset()

    /** Fin de vie : moteur LiteRT et voix fermés (terminal). */
    fun close() {
        wiring.close()
        engine.close()
    }
}
