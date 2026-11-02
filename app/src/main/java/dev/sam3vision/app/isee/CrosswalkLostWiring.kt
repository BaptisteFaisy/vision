package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingGuidance
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.crossing.CrossingLostWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Cran de la perte du passage en Traversée : le verdict (100), devant le
 * latéral (101, « Gauche. » / « Droite. ») — la perte coupe l'alignement en
 * cours, le verdict suivant (répété toutes les 2 s) reprend derrière. Pur,
 * testé en JVM.
 */
internal fun crossingLostLevel(): Int = CrossingLevels.VERDICT

/**
 * Mémoire de perte, une par processus (miroir de [CrossingLateralWatch]) : la
 * sonde l'alimente à chaque frame publiée en Traversée, l'écran observe
 * [flow] (phrase une fois par perte, jamais de redite).
 */
internal object CrosswalkLostWatch {

    private val watcher = CrossingLostWatcher()
    private val _flow = MutableStateFlow<String?>(null)

    /** Dernière phrase de perte (`null` = silence ou passage en vue). */
    val flow: StateFlow<String?> = _flow

    /** Passage suivi [visible] à [atMs] : publie la phrase de perte, ou `null` (silence). */
    fun update(visible: Boolean, atMs: Long, language: SpeechLanguage): String? {
        val phrase = watcher.onFrame(visible, atMs, language)?.phrase
        _flow.value = phrase
        return phrase
    }

    /** Nouvelle séance, ou sortie de Traversée : l'absence est oubliée. */
    fun reset() {
        watcher.reset()
        _flow.value = null
    }
}

/**
 * Alimente la perte depuis la sonde : en Traversée, le même passage que le
 * latéral suit ([CrossingGuidance.selectBest], sans géométrie comme lui) ;
 * hors Traversée, la mémoire retombe (une rentrée sans passage attend 3 s,
 * elle ne parle pas aussitôt).
 */
internal fun feedCrosswalkLost(
    traversing: Boolean,
    objects: List<SegObject>,
    atMs: Long,
    language: SpeechLanguage,
) {
    if (!traversing) {
        CrosswalkLostWatch.reset()
        return
    }
    CrosswalkLostWatch.update(CrossingGuidance.selectBest(objects) != null, atMs, language)
}

/**
 * Voix du passage (mot + approche + arrivée + perte) : le latéral existant
 * plus la perte — un seul appel côté écran (plafond 500 lignes).
 */
internal fun observeCrossingVoices(
    scope: CoroutineScope,
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
    crossingActive: () -> Boolean = { false },
) {
    observeCrossingVoice(scope, placeMode, crossingActive)
    observeCrosswalkLost(scope, placeMode, crossingActive)
}

/**
 * Voix de la perte sur le bus (niveau verdict, devant le latéral 101) : une
 * fois par perte, sans file ni rattrapage — l'écrasée n'est jamais redite.
 * En Traversée dehors uniquement.
 */
internal fun observeCrosswalkLost(
    scope: CoroutineScope,
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
    crossingActive: () -> Boolean = { false },
) {
    scope.launch {
        CrosswalkLostWatch.flow.collect { phrase ->
            if (phrase != null && placeMode() == PictoPlaceMode.DEHORS && crossingActive() &&
                AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_LOST, System.currentTimeMillis())
            ) {
                VoiceBus.post(crossingLostLevel(), phrase)
            }
        }
    }
}
