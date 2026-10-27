package dev.sam3vision.app.glasses

/**
 * Le micro des lunettes : ce qu'on demande au flux, ce qui se décide quand il refuse, et
 * la mise au format de la dictée. Tout est pur — ni Android, ni SDK DAT — donc testable en
 * JVM ; `GlassesCamera` traduit ces choix en `AudioCodec`, `GlassesAudio` les applique.
 */
data class AudioChoice(val hertz: Int, val channels: Int) {
    /** Aucune piste audio demandée : le flux ne sert alors qu'à la photo. */
    val silent: Boolean get() = hertz <= 0
}

object GlassesAudioPlan {

    /** 16 kHz mono : exactement le format de la dictée, aucun rééchantillonnage. */
    val PREFERRED = AudioChoice(hertz = PcmRate.DOWNSTREAM_HZ, channels = 1)

    /** Repli quand le flux refuse 16 kHz : 48 kHz mono, ramené à 16 kHz à l'arrivée. */
    val FALLBACK = AudioChoice(hertz = 48_000, channels = 1)

    /** Micro non autorisé côté app Meta AI : on garde la caméra seule, sans code mort. */
    val SILENT = AudioChoice(hertz = 0, channels = 0)

    /**
     * Essais dans l'ordre, du meilleur au dernier recours. Caméra seule en dernier : un
     * codec audio refusé ne doit pas priver l'app de la caméra.
     */
    fun ladder(audioAllowed: Boolean): List<AudioChoice> =
        if (audioAllowed) listOf(PREFERRED, FALLBACK, SILENT) else listOf(SILENT)

    /**
     * La dictée ne prend le micro des lunettes que si le flux tourne **et** que le micro est
     * autorisé ; sinon elle garde celui du téléphone (aucune régression, aucun silence muet).
     */
    fun usable(connected: Boolean, streaming: Boolean, audioAllowed: Boolean): Boolean =
        connected && streaming && audioAllowed
}

/** Mise au format de la dictée : PCM 16 bits mono, 16 kHz. */
object PcmRate {

    /** Fréquence attendue par la transcription (`Wav.SAMPLE_RATE`, côté `:core`). */
    const val DOWNSTREAM_HZ = 16_000

    /**
     * PCM 16 bits mono à [sourceRate] -> 16 kHz, par moyenne de `sourceRate / 16 000`
     * échantillons. Facteur entier seulement (48 000 -> 3, le seul cas du repli) : sinon le
     * PCM est rendu tel quel plutôt que rééchantillonné de travers.
     */
    fun to16k(pcm: ByteArray, sourceRate: Int): ByteArray {
        val factor = sourceRate / DOWNSTREAM_HZ
        if (sourceRate % DOWNSTREAM_HZ != 0 || factor < 2) return pcm
        val outCount = (pcm.size / 2) / factor
        val out = ByteArray(outCount * 2)
        for (i in 0 until outCount) {
            var sum = 0
            for (j in 0 until factor) sum += sampleAt(pcm, i * factor + j)
            val mean = (sum / factor).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (mean and 0xFF).toByte()
            out[i * 2 + 1] = ((mean shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Échantillon 16 bits signé, petit-boutiste. */
    private fun sampleAt(pcm: ByteArray, index: Int): Int {
        val at = index * 2
        val low = pcm[at].toInt() and 0xFF
        return low or (pcm[at + 1].toInt() shl 8)
    }
}