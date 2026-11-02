package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.FindIndoorLevels
import dev.sam3vision.core.picto.PlaceModeOrder as CorePlaceModeOrder
import dev.sam3vision.core.picto.PlaceModeText
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.IseeClipsEn

/**
 * Ordre vocal « mode dedans/dehors » : bascule le lieu du picto et le
 * confirme à voix haute. Appelé sur le texte dicté en premier, avant la
 * recherche et les pictos : la bascule marche même en mode dehors, où tout
 * le reste du picto est muet. Vers dehors, l'état picto affiché est vidé
 * (les repères restent armés pour le retour dedans) et la chasse pendante
 * est effacée ([onLeaveIndoor], idempotent : rappeler « mode dehors »
 * dehors ne coûte rien). Le texte qui n'est pas un ordre de lieu rend
 * `false` et poursuit son chemin (recherche, picto, cap, navigation, LLM).
 *
 * La confirmation part en clip anglais ([post], signature de VoiceBus :
 * niveau, texte, clip) — zéro appel réseau, audible hors-ligne — au cran
 * find, comme les confirmations « find ». [speak] garde la synthèse
 * historique (contrat des tests directs) ; la production ([IseeModeOrders])
 * le neutralise pour ne pas doubler le clip.
 */
class PlaceModeOrder(
    private val settings: () -> Settings,
    private val speak: suspend (String) -> Unit,
    private val update: StateUpdate,
    private val onLeaveIndoor: () -> Unit = {},
    private val onPlaceMode: (PictoPlaceMode) -> Unit = {},
    private val post: (level: Int, text: String, clipId: String?) -> Unit = { _, _, _ -> },
) {

    /**
     * Rend `true` quand [text] ordonnait une bascule (texte consommé).
     * L'écran affiche la confirmation, dite par le chemin navigation.
     */
    suspend fun run(text: String): Boolean {
        val mode = CorePlaceModeOrder.parse(text) ?: return false
        apply(mode, PlaceModeText.switched(mode, settings().speechLanguage))
        return true
    }

    /**
     * Applique [mode] : chasse vidée vers dehors, état (lieu, statut,
     * picto), sonde via [onPlaceMode], confirmation en clip local
     * ([post]) puis synthèse historique ([speak]). Dictée ([run]) et
     * geste (quadruple tap) partagent ce chemin.
     */
    suspend fun apply(mode: PictoPlaceMode, confirm: String) {
        if (!mode.pictoEnabled) onLeaveIndoor()
        val (clipId, clipText) = placeConfirmClip(mode)
        update {
            it.copy(
                placeMode = mode,
                status = confirm,
                picto = if (mode.pictoEnabled) it.picto else it.picto.copy(
                    phase = PictoPhase.Idle,
                    hits = emptyList(),
                ),
            )
        }
        onPlaceMode(mode)
        post(FindIndoorLevels.FIND, clipText, clipId)
        speak(confirm)
    }
}

/**
 * Confirmation de bascule : clip existant du pack voix, jamais de MP3
 * généré — `indoor_on` (« Indoor mode on. ») dedans, `outdoor_on`
 * (« Outdoor mode on. ») dehors. Les deux fichiers (`vp_*_en.mp3`)
 * sont embarqués dans `res/raw`.
 */
private fun placeConfirmClip(mode: PictoPlaceMode): Pair<String, String> =
    if (mode.pictoEnabled) IseeClipsEn.INDOOR_ON to IseeClipsEn.INDOOR_ON_TEXT
    else IseeClipsEn.OUTDOOR_ON to IseeClipsEn.OUTDOOR_ON_TEXT
