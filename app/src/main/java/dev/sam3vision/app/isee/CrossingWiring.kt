package dev.sam3vision.app.isee

import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.TrainedLightColor
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
 * Signal traversée de la frame, ou `null` pour rester muet : pas de feu en
 * vue (doute annoncé une fois par [CrossingAnnouncer], puis silence), image
 * illisible, ou état déjà annoncé.
 */
internal fun crossingCueOf(
    announcer: CrossingAnnouncer,
    frame: LiveFrame,
    jpegB64: String,
    atMs: Long,
    language: SpeechLanguage,
): CrossingCue? {
    val box = CrossingPrompts.selectLightBox(frame.objects, frame.width, frame.height)
    val crop = if (box == null) null else CrossingPixels.frame(jpegB64, box)
    val state = if (crop == null) CrossingState.Unknown
    else TrainedLightColor.classify(crop.argb, crop.width, crop.height)
    return announcer.onFrame(atMs, state, language)
}

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
