package dev.sam3vision.app.depth

import android.util.Log
import dev.sam3vision.core.depth.DepthReadings
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections

/**
 * Mètre de profondeur du direct : médianes de proximité des objets, en mètres
 * calibrés par les ancres sténopé de la frame. Une seule implémentation en
 * production ([ServerDepthMeter]) ; les tests y branchent un faux, sans réseau.
 */
interface DepthMeter {
    /** Lectures affinées par la profondeur, ou le sténopé tel quel. */
    fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>): List<DistanceReading>

    /** Oublie tout état de séance (aucun aujourd'hui : la profondeur est celle de la frame). */
    fun reset()
}

/**
 * Profondeur **du serveur** : la trame demande `with_depth` (voir `LiveProbe`)
 * et chaque réponse porte la proximité de ses objets (`SegObject.depth`, 0..1,
 * grand = proche). Aucune inférence sur le téléphone — la carte monoculaire
 * (Depth Anything V2 Small) tourne sur le GPU Modal, une fois par 8 frames, et
 * le serveur répète ses médianes entre deux : `refineStatusWithCloseness` les
 * remet en mètres. Réponse sans médiane (backend sans modèle, ancien backend) :
 * le sténopé passe tel quel, jamais d'erreur. Un objet DEPTH_ONLY resté sans
 * distance est journalisé (`Log.w`, cause incluse), jamais tu.
 */
object ServerDepthMeter : DepthMeter {
    override fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>): List<DistanceReading> {
        return try {
            val closeness = DepthReadings.serverCloseness(frame.objects)
            if (closeness.isEmpty()) {
                pinhole
            } else {
                DepthReadings.refineStatusWithCloseness(
                    frame.objects.toDetections(frame.width, frame.height),
                    pinhole,
                    closeness,
                    onUnmeasured = { miss ->
                        val detail = "${miss.label} ${miss.reason} (bas coupe=${miss.cutBottom})"
                        Log.w("sam3vision", "sans distance : $detail")
                    },
                ).readings
            }
        } catch (e: Exception) {
            pinhole
        }
    }

    override fun reset() = Unit
}
