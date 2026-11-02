package dev.sam3vision.app.isee

import dev.sam3vision.core.exposure.ClipStats
import java.util.concurrent.atomic.AtomicLong

/**
 * Une mesure d'écrêtage de la lampe, telle que publiée par la lecture du feu :
 * [box] normalisée 0..1 (celle de `CrossingPrompts.selectLightBoxes`, `null` si aucune
 * boîte vue) et [stats] du crop classifié. [seq] augmente à chaque publication : le
 * consommateur détecte une mesure neuve **sans comparer d'horloges** — la sonde et le fil
 * d'analyse n'ont pas la même (epoch contre `elapsedRealtime`).
 */
internal data class LightExposure(val box: List<Float>?, val stats: ClipStats, val seq: Long)

/**
 * Dernière mesure d'écrêtage du feu, pour le pilotage capteur (`LiveSensorControl`) :
 * même motif que [SharedCameraFrame], une écriture `@Volatile` d'un objet immuable, aucun
 * verrou, aucun couplage nouveau entre la sonde et la caméra.
 *
 * Alimentée une fois par frame par `crossingLightOf` — donc **seulement** quand la
 * traversée lit un feu : hors traversée, rien n'est publié et le capteur n'est pas piloté.
 */
internal object LightExposureSignal {

    private val counter = AtomicLong()

    @Volatile
    private var current: LightExposure? = null

    /** Publie la mesure de la frame (box `null` = pas de feu en vue, mesure non nulle quand même). */
    fun offer(box: List<Float>?, stats: ClipStats) {
        current = LightExposure(box, stats, counter.incrementAndGet())
    }

    /** Dernière mesure, `null` avant la première (ou après [clear]). */
    fun latest(): LightExposure? = current

    /** Fin de traversée : plus aucune mesure à consommer. */
    fun clear() {
        current = null
    }
}
