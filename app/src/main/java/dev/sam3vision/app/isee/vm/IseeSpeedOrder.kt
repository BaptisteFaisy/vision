package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.TtsSpeedCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Ordre vocal de vitesse (« vitesse 1,5 », « parle plus vite ») : réglage
 * persisté, lecteur à jour, confirmation dite à la nouvelle vitesse.
 * Extrait d'`IseeViewModel` (plafond 500 lignes) sans changer une ligne
 * de comportement : même parseur, même persistance, même voix.
 */
class IseeSpeedOrder(
    private val settings: () -> Settings,
    /** Réglages remplacés + persistés (fire-and-forget chez l'appelant). */
    private val persist: (Settings) -> Unit,
    /** Vitesse du lecteur appliquée aussitôt. */
    private val setSpeed: (Float) -> Unit,
    /** Tenue annulée : micro rendu, motif affiché. */
    private val dropHeard: (String?) -> Unit,
    private val scope: CoroutineScope,
    private val synthesize: suspend (String) -> ByteArray,
    private val speakBytes: suspend (text: String, audio: ByteArray) -> Unit,
) {

    /**
     * Vrai si [text] portait un ordre de vitesse (la dictée ne part pas
     * au LLM). Sans clé ElevenLabs, le texte reste affiché et la vitesse
     * s'applique aux prochaines lectures : un ordre vocal n'échoue
     * jamais à voix haute.
     */
    fun run(text: String): Boolean {
        val current = settings()
        val speed = TtsSpeedCommand.parse(text, current.ttsSpeed) ?: return false
        persist(current.copy(ttsSpeed = speed))
        setSpeed(speed)
        val confirmation = TtsSpeedCommand.spoken(speed, current.speechLanguage)
        dropHeard(confirmation)
        scope.launch {
            val bytes = synthesize(confirmation)
            if (bytes.isNotEmpty()) speakBytes(confirmation, bytes)
        }
        return true
    }
}
