package dev.sam3vision.app.segment

import dev.sam3vision.core.segment.SegmentMask

/**
 * Mètre de segmentation du direct : la caméra iSee lui offre chaque frame
 * capturée et lit le masque praticable frais pour la flèche et la voix. Une
 * seule implémentation ([LiveSegmentMeter]) ; les tests y branchent un faux,
 * sans modèle ni Android.
 */
interface SegmentMeter {
    /** Dernière frame à segmenter (les intermédiaires sont jetées, pas empilées). */
    fun offer(jpegB64: String)

    /** Masque praticable frais, `null` s'il est périmé ou jamais calculé. */
    fun latestFresh(): SegmentMask?

    /** Oublie file, masque et cadence (nouvelle session, moteur gardé chaud). */
    fun reset()

    /** Fin de vie : moteur fermé (terminal). */
    fun close()
}
