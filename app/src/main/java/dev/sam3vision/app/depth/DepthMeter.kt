package dev.sam3vision.app.depth

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame

/**
 * Mètre de profondeur du direct : la sonde lui offre chaque frame capturée et
 * lui demande les lectures affinées à la publication. Une seule implémentation
 * ([LiveDepthMeter]) ; les tests y branchent un faux, sans modèle ni Android.
 */
interface DepthMeter {
    /** Dernière frame à inférer (les intermédiaires sont jetées, pas empilées). */
    fun offer(jpegB64: String)

    /** Lectures affinées par la carte de profondeur, ou le sténopé tel quel. */
    fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>): List<DistanceReading>

    /** Oublie carte, moteur et file (nouvelle session). */
    fun reset()
}
