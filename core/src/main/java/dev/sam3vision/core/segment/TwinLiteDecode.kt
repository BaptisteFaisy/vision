package dev.sam3vision.core.segment

/**
 * Décodage TwinLiteNet, miroir de la sample app du zoo (`TwinLiteSegmenter`,
 * MIT) : argmax sur les 2 classes de chaque tête. Pur JVM : testé sans
 * appareil.
 *
 * Contrat vérifié le 2026-10-28 (inférence locale sur image route) : sortie 0
 * = zone praticable, sortie 1 = voies, logits bruts (comparaison directe,
 * pas de softmax nécessaire pour l'argmax).
 */
object TwinLiteDecode {

    const val INPUT_WIDTH = 640
    const val INPUT_HEIGHT = 360
    const val CLASSES = 2

    /** Indice des têtes dans les sorties du modèle. */
    const val HEAD_DRIVEABLE = 0
    const val HEAD_LANES = 1

    /**
     * Logits aplatis [2×plan] par tête (plan classe 0 puis plan classe 1,
     * ordre NCHW) → masques binaires. Forme inattendue ⇒ `null` (jamais
     * d'exception vers l'appelant).
     */
    fun decode(outputs: List<FloatArray>, width: Int, height: Int): Pair<SegmentMask, SegmentMask>? {
        if (outputs.size != 2) return null
        val plane = width * height
        if (plane <= 0) return null
        val drive = argmax(outputs[HEAD_DRIVEABLE], plane) ?: return null
        val lanes = argmax(outputs[HEAD_LANES], plane) ?: return null
        return SegmentMask(width, height, drive) to SegmentMask(width, height, lanes)
    }

    /** Argmax classe 0 / classe 1 par pixel ; égalité ⇒ fond (comme le zoo). */
    private fun argmax(logits: FloatArray, plane: Int): ByteArray? {
        if (logits.size < 2 * plane) return null
        return ByteArray(plane) { i -> if (logits[plane + i] > logits[i]) 1 else 0 }
    }
}
