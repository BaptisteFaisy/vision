package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings

/**
 * Les ordres de **mode** de l'accueil iSee, dans l'ordre où le routeur les
 * rejoue : recherche (« mode find my X ») d'abord, puis lieu (« mode
 * dehors », « mode dedans »). Un ordre de mode ne fait aucun appel réseau
 * lui-même (la génération du picto, elle, en fait un) et ne part jamais
 * au LLM.
 *
 * Construits ensemble parce qu'ils partagent tout (settings, voix, état) et
 * que l'ordre d'essai doit rester écrit à un seul endroit : une phrase qui
 * n'est pas un ordre de mode rend `false` et poursuit son chemin. La
 * recherche passe avant le lieu : « mode find my indoor plant » nomme une
 * plante, pas le mode dedans.
 *
 * Spec v4 : « mode … » est le seul interrupteur (les anciens ordres sans
 * « mode » — maison, find, picto, where, où sont — partent au LLM).
 * [onFindOff] efface la chasse pendante (sortie de dedans).
 */
class IseeModeOrders(
    settings: () -> Settings,
    speak: suspend (String) -> Unit,
    update: StateUpdate,
    onFind: (String) -> Unit = {},
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEDANS },
    onFindOff: () -> Unit = {},
    onPlaceMode: (PictoPlaceMode) -> Unit = {},
) {

    private val find = ModeFindVoice(settings, speak, update, onFind = onFind, placeMode = placeMode)
    private val place = PlaceModeOrder(settings, speak, update, onLeaveIndoor = onFindOff, onPlaceMode = onPlaceMode)

    /** Bascule geste (quadruple tap) : même application que l'ordre vocal, annonce fournie. */
    suspend fun applyPlace(mode: PictoPlaceMode, confirm: String) = place.apply(mode, confirm)

    /** Vrai si [text] portait un ordre de mode (texte consommé, rien d'autre à faire). */
    suspend fun run(text: String): Boolean =
        find.run(text) || place.run(text)
}
