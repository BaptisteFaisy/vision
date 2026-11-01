package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PlaceModeOrder as CorePlaceModeOrder
import dev.sam3vision.core.picto.PlaceModeText
import dev.sam3vision.core.settings.Settings

/**
 * Ordre vocal « mode dedans/dehors » : bascule le lieu du picto et le
 * confirme à voix haute. Appelé sur le texte dicté en premier, avant la
 * maison et les pictos : la bascule marche même en mode dehors, où tout le
 * reste du picto est muet. Vers dehors, l'état picto affiché est vidé
 * (les repères restent armés pour le retour dedans). Le texte qui n'est
 * pas un ordre de lieu rend `false` et poursuit son chemin (maison,
 * picto, cap, navigation, LLM).
 */
class PlaceModeOrder(
    private val settings: () -> Settings,
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
) {

    /**
     * Rend `true` quand [text] ordonnait une bascule (texte consommé).
     * L'écran affiche la confirmation, dite par le chemin navigation.
     */
    suspend fun run(text: String): Boolean {
        val mode = CorePlaceModeOrder.parse(text) ?: return false
        val confirm = PlaceModeText.switched(mode, settings().speechLanguage)
        update {
            it.copy(
                placeMode = mode,
                status = confirm,
                picto = if (mode.pictoEnabled) it.picto else it.picto.copy(
                    phase = PictoPhase.Idle,
                    draftPng = null,
                    draftLabel = "",
                    hits = emptyList(),
                ),
            )
        }
        speak(confirm)
        return true
    }
}
