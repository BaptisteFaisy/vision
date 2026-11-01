package dev.sam3vision.app.isee

import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairConcepts
import dev.sam3vision.core.stairs.StairCountVoice
import dev.sam3vision.core.stairs.StairLabelSignal
import dev.sam3vision.core.stairs.StairLabelSignals
import dev.sam3vision.core.stairs.StairRoi
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guetteur escaliers de la sonde, miroir du trottoir : l'escalier nommé par
 * SAM, lu dans les lectures de distance d'abord (boîte → sens + heure),
 * sinon dans les libellés bruts (sans mesure : l'escalier n'a ni hauteur
 * connue, ni pose, ni ancre garantie — mais SAM le nomme, et la voix
 * compte quand même les marches vues). Le sens vient des mots du libellé
 * (« stairs going up », exact) sinon de la hauteur de la boîte
 * ([StairLabelSignals]) ; l'annonce est douce (attend le silence) : une info
 * de navigation, jamais une consigne d'évitement. Coupé en mode Traversée.
 * Voix montée : compte à rebours (« Plus que N marches. ») puis dernière
 * marche, par compteur Canny frais de la caméra ([StairCannyCount] — 0 = non
 * compté, pas « zéro marche ») ou par profondeur (< 1 m, lue ici). Lecture
 * séquentielle, comme le trottoir : une alerte confirmée ne s'annule pas.
 * [stairsAudible] ignore la sourdine GPS (chat libre suffit) : la sécurité
 * passe avant le silence (contrat `IseeVoiceGate`). L'esquive en cours
 * ([stairsYieldToAvoidance]) tait l'annonce : les humains priment, même sur
 * les escaliers.
 */
internal fun LiveProbe.observeStairs(
    scope: CoroutineScope,
    voice: ProximityVoice,
    stairsAudible: () -> Boolean,
    language: () -> SpeechLanguage,
): StairWatcher {
    // Deux frames suffisent côté voix (le flux Canny d'affichage garde trois) :
    // un escalier nommé deux fois de suite n'est plus un clignotement.
    val watcher = StairWatcher(confirmFrames = 2)
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            if (this@observeStairs.stairsYieldToAvoidance()) return@collect
            // Lectures d'abord (sens + heure par la boîte), libellés bruts en
            // repli : un escalier nommé sans mesure reste un escalier.
            val readings = st.distanceLayer?.readings.orEmpty()
            val found = stairsFoundOf(st)
            // Calibrage SAM : les frames suivantes ne comptent que dans la
            // boîte de l'escalier retenu (effacée sinon : balayage entier).
            StairCannyCount.setRoi(stairsRoiOf(found, st.width, st.height))
            // Profondeur du marchement le plus proche : la dernière marche par
            // profondeur (< 1 m) parle même quand le compteur est indisponible
            // (caméra coupée, repli bitmap, compte périmé : 0 = non compté).
            val stairsMeters = readings.filter { StairConcepts.isStairs(it.label) }
                .mapNotNull { it.meters.takeIf { m -> m.isFinite() } }.minOrNull()
            // Compte Canny frais de la caméra (0 = non compté) : il nourrit le
            // compte à rebours de la montée (« Plus que N marches. »).
            val stairsCount = StairCannyCount.lineCount()
            val signal = if (found.detected) {
                StairSignal(detected = true, confidence = 1f, lineCount = stairsCount, roiTop = 0, roiBottom = 0)
            } else {
                StairSignal.none()
            }
            val cue = watcher.onSignal(
                signal, System.currentTimeMillis(), language(), found.direction, found.centerX, stairsMeters)
            if (found.detected) {
                android.util.Log.d(
                    "sam3vision",
                    "escaliers: n=$stairsCount ageMs=${StairCannyCount.countAgeMs()} " +
                        "m=$stairsMeters annonce=${cue?.phrase}",
                )
            }
            cue ?: return@collect
            voice.onPhrase(cue.phrase, stairsAudible(), urgent = false)
        }
    }
    return watcher
}

/**
 * Escalier nommé par SAM dans l'état : lectures de distance d'abord (sens +
 * heure par la boîte), libellés bruts en repli (sans mesure : l'escalier n'a
 * ni hauteur connue, ni pose, ni ancre garantie — mais SAM le nomme, et la
 * voix compte quand même les marches vues). Pur, partagé par le guetteur et
 * la voix du compteur.
 */
internal fun stairsFoundOf(st: LiveProbeState): StairLabelSignal {
    val readings = st.distanceLayer?.readings.orEmpty()
    return StairLabelSignals.of(readings, st.width, st.height)
        .takeIf { it.detected } ?: StairLabelSignals.ofLabels(st.labels)
}

/**
 * ROI du compteur dans la boîte SAM de l'escalier (`null` = pas d'escalier
 * localisé : le compteur balaie l'image entière). Boîte pixels → normalisée
 * (miroir de `Detection.normalizedBox`) ; dégénérée = `null`. Pur, testé.
 */
internal fun stairsRoiOf(found: StairLabelSignal, width: Int, height: Int): StairRoi? {
    if (!found.detected || width <= 0 || height <= 0 || found.box.size < 4) return null
    return StairRoi(
        found.box[0] / width, found.box[1] / height, found.box[2] / width, found.box[3] / height,
    ).normalized()
}

/**
 * Esquive en cours (décalage humain ou couloir bouché) : les voix escaliers
 * se taisent — l'évitement prime, même sur les escaliers. Pur hors sonde
 * (lit la dernière décision guidée, posée à chaque frame en mode guidage).
 */
internal fun LiveProbe.stairsYieldToAvoidance(): Boolean {
    if (voice.cue()?.urgent == true) return true
    return voice.lastDecision?.blocked == true
}

/**
 * Voix du compteur de marches : toutes les [tickMs] (300 ms), quand SAM nomme
 * un escalier et que la caméra voit des marches fraîches, dit combien
 * (« 5 marches. ») — changements aussitôt, même compte répété toutes les
 * 1 s ([StairCountVoice]), en prioritaire ([VoicePreempt.STAIRS] : coupe la
 * parole douce, chat compris — sécurité et parole du user exceptées), coupé
 * en Traversée comme en esquive ([stairsYieldToAvoidance]). La voix part en
 * coroutine fille : le battement ne s'arrête jamais pour une synthèse lente
 * (le lecteur coupe le précédent, dernier gagnant). Sans escalier nommé ou
 * sans compte frais : silence (auto-muet caméra coupée, séance finie). Rend
 * le compteur pour le `reset()` de séance. [stairsAudible] ignore la sourdine
 * GPS, comme l'annonce (sécurité). [nowMs] est l'horloge du compteur
 * (injectée en test).
 */
internal fun LiveProbe.observeStairsCount(
    scope: CoroutineScope,
    voice: ProximityVoice,
    stairsAudible: () -> Boolean,
    language: () -> SpeechLanguage,
    tickMs: Long = StairCountVoice.COUNT_MS,
    nowMs: () -> Long = { System.currentTimeMillis() },
): StairCountVoice {
    val counter = StairCountVoice()
    scope.launch {
        while (true) {
            delay(tickMs)
            val st = state.value
            if (st.crossingActive) continue
            if (this@observeStairsCount.stairsYieldToAvoidance()) continue
            if (!stairsFoundOf(st).detected) continue
            val phrase = counter.onTick(
                true, StairCannyCount.lineCount(), nowMs(), language(),
            ) ?: continue
            scope.launch {
                voice.onPhrase(
                    phrase, stairsAudible(), urgent = false,
                    dedupMs = StairCountVoice.REPEAT_MS, preempt = VoicePreempt.STAIRS,
                )
            }
        }
    }
    return counter
}
