package dev.sam3vision.app.isee

import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.PedLightGate
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Câblage du **mode Traversée** de la sonde : prompts feu+voitures, couleur du
 * feu lue dans la frame, annonces et vibrations.
 *
 * Isolé de [LiveProbe] pour que celle-ci reste sous la limite de 500 lignes
 * (même raison que `ProbeWiring.kt`) : la sonde ne porte que l'annonceur, le
 * fanion d'état et l'appel publié.
 */

/**
 * Active ou coupe le mode Traversée : les prompts deviennent feu+voitures (ou
 * retombent sur les défauts), la session repart de zéro comme à tout
 * changement de Réglages. Le guidage obstacles est coupé tant que le mode est
 * actif : « Stop, mur » et « Vert, traversez » ne doivent jamais se mélanger.
 */
internal fun LiveProbe.setCrossingActive(settings: Settings, active: Boolean) {
    val prompts = if (active) CrossingPrompts.PROMPTS else LivePrompts.default
    configureFrom(settings, prompts, active)
}

/**
 * Le feu d'une frame : **où** il est ([box], normalisée 0..1) et **ce qu'il dit**
 * ([state]). Les deux viennent de la même lecture, donc l'étiquette posée sur la
 * vidéo ne peut pas désigner un autre feu que celui qui a été classifié.
 */
internal data class CrossingLight(
    val box: List<Float>?,
    val state: CrossingState,
)

/**
 * Lecture du feu sur une frame : boîte SAM au meilleur score, crop JPEG classifié
 * en local puis filtré par la porte anti-confusion ([PedLightGate], 100 %
 * embarqué, aucun réseau). Pas de feu en vue ou image illisible ⇒
 * [CrossingState.Unknown] (le doute vaut « attendez »).
 *
 * Appelée **une fois par frame** par la sonde : le décodage du crop est le poste
 * coûteux, le refaire pour l'affichage doublerait le travail.
 */
internal fun crossingLightOf(frame: LiveFrame, jpegB64: String): CrossingLight {
    val box = CrossingPrompts.selectLightBox(frame.objects, frame.width, frame.height)
        ?: return CrossingLight(null, CrossingState.Unknown)
    val crop = CrossingPixels.frame(jpegB64, box)
        ?: return CrossingLight(box, CrossingState.Unknown)
    return CrossingLight(box, PedLightGate.verdict(crop.argb, crop.width, crop.height))
}

/**
 * Ce que la sonde publie pour le feu sur une frame : la phrase à dire
 * ([cue], `null` = rester muet), l'état affiché et la boîte à marquer sur la
 * vidéo. Un seul aller-retour, pour que l'étiquette ne désigne jamais un autre
 * feu que celui qui vient d'être annoncé.
 */
internal data class CrossingPatch(
    val cue: CrossingCue?,
    val state: CrossingState,
    val box: List<Float>?,
)

/**
 * Signal traversée de la frame, ou `null` quand le mode est coupé : la sonde
 * reste alors muette et ne marque aucun feu.
 */
internal fun crossingPatchOf(
    announcer: CrossingAnnouncer,
    active: Boolean,
    frame: LiveFrame,
    jpegB64: String,
    atMs: Long,
    language: SpeechLanguage,
): CrossingPatch? {
    if (!active) return null
    val light = crossingLightOf(frame, jpegB64)
    return CrossingPatch(announcer.onFrame(atMs, light.state, language), light.state, light.box)
}

/**
 * État du feu sur une frame. Conservé pour le direct (`TraverseWiring`), qui n'a
 * pas d'affichage à nourrir : il ne veut que la couleur.
 */
internal fun crossingStateOf(frame: LiveFrame, jpegB64: String): CrossingState =
    crossingLightOf(frame, jpegB64).state

/**
 * Chaque nouvel état du feu fait vibrer le téléphone (vert = double salve,
 * rouge = créneau long), en plus de la voix déjà câblée par `observeSpoken`.
 */
internal fun LiveProbe.observeCrossing(scope: CoroutineScope, haptics: IseeHaptics) {
    var last: CrossingState? = null
    scope.launch {
        state.collect { st ->
            val crossing = st.crossing
            if (!st.crossingActive || crossing == null || crossing == last) return@collect
            last = crossing
            haptics.crossing(crossing)
        }
    }
}

/**
 * Chaque bascule du mode Traversée (bouton, ordre vocal, guidage auto)
 * est signalée à [onChanged] : le guidage y reprend la parole après une
 * coupe manuelle. Sans répétition (front seul).
 */
internal fun LiveProbe.observeCrossingActive(scope: CoroutineScope, onChanged: (Boolean) -> Unit) {
    var last: Boolean? = null
    scope.launch {
        state.collect { st ->
            val active = st.crossingActive
            if (active == last) return@collect
            last = active
            runCatching { onChanged(active) }
        }
    }
}

/**
 * Libellé du bouton Traversée : l'état du mode et, quand il est actif, le
 * dernier état connu du feu. Pur, testé en JVM.
 */
internal fun crossingButtonLabel(active: Boolean, state: CrossingState?): String {
    if (!active) return "Traversée : OFF"
    return when (state) {
        CrossingState.Green -> "Traversée : VERT"
        CrossingState.Red -> "Traversée : ROUGE"
        CrossingState.Unknown, null -> "Traversée : …"
    }
}
