package dev.sam3vision.app.ihear

import dev.sam3vision.app.IHEAR_INTRO_TEXT
import dev.sam3vision.core.iseedesign.IseeScene

/** Ce que le design affiche du message courant : la scène, le petit libellé au-dessus
 * du gros texte (YOU / ISEE, l'un ou l'autre, jamais les titres de machine), et le
 * texte à montrer en gros. Pure fonction : testable sur JVM. */
data class IseeSceneView(
    val scene: IseeScene,
    val label: String?,
    val heading: String,
    /** Vrai quand le gros texte est parlé par le user (couleur bleue du design). */
    val fromUser: Boolean,
)

/** Message actuellement sélectionné dans la vue : l'intro (index 0), puis les bulles
 * de l'historique. Utilisé au-dessus du gros texte et pour le look de la bulle. */
fun selectedEntry(state: IhearUiState): ChatEntry? = when {
    state.selected <= 0 -> ChatEntry.IntroBubble(INTRO_BUBBLE_ID, IHEAR_INTRO_TEXT)
    else -> state.entries.getOrNull(state.selected - 1)
}

/** La scène qui s'affiche au moment t, dans l'ordre de priorité du design :
 * écoute > réflexion > enregistrement > voix > la vue du message courant.
 *
 * La réflexion passe devant l'enregistrement : c'est le seul retour visible quand
 * la question part (la carte caméra, elle, reste affichée — voir `iseeSceneLayers`). */
fun iseeSceneOf(state: IhearUiState, entry: ChatEntry?): IseeScene = when {
    state.holding || state.transcribing -> IseeScene.LISTENING
    state.thinking -> IseeScene.THINKING
    state.recordingVideo || state.photoPath != null -> IseeScene.VISION
    state.speakingEntryId != null -> IseeScene.VOICE
    entry == null -> IseeScene.READY
    entry is ChatEntry.UserBubble && isLast(state) -> IseeScene.READY
    isLast(state) -> IseeScene.ANSWERED
    else -> IseeScene.MEMORY
}

/** Le gros texte est celui du dernier message (plus récent) ? */
private fun isLast(state: IhearUiState): Boolean = state.selected >= state.entries.size

/** Vue complète du message courant. */
fun iseeSceneView(state: IhearUiState, entry: ChatEntry?): IseeSceneView {
    val fromUser = entry is ChatEntry.UserBubble
    return IseeSceneView(
        scene = iseeSceneOf(state, entry),
        label = labelOf(entry),
        heading = entry?.text.orEmpty(),
        fromUser = fromUser,
    )
}

/** Libellés au-dessus du gros texte : YOU quand le user parle, ISEE sinon. Les autres
 * titres de la vidéo (vision, thinking…) sont supprimés — décision conservée ici. */
fun labelOf(entry: ChatEntry?): String? = when {
    entry is ChatEntry.UserBubble -> "YOU"
    entry != null -> "ISEE"
    else -> null
}
