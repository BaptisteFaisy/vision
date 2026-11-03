package dev.sam3vision.app.glasses

/**
 * Porte anti-bruit des questions lunettes : une écoute accidentelle (bouton
 * heurté, écoute auto à l'ouverture, délai de 20 s sans parole) ne doit ni
 * appeler la transcription, ni a fortiori le LLM avec photo. Deux barrages,
 * purs et testés en JVM :
 *
 * - [hasSpeech] : le PCM 16 kHz mono 16 bits doit porter au moins 50 ms
 *   au-dessus du plancher — en dessous, silence ou clic, on jette sans bruit ;
 * - [isJunkTranscript] : les fantômes classiques des moteurs (transcrits sur
 *   du bruit) ne partent jamais au LLM.
 *
 * Seuils conservateurs : ne filtrer que le quasi-silence et les scories
 * connues — une vraie question basse ne doit jamais être jetée. Mesurer avant
 * de durcir (`LUNETTES-RECETTE-TEL`).
 */
internal object GlassesSpeechGate {

    /** Plancher : en dessous, bruit de fond, pas de parole (−36 dBFS environ). */
    private const val FLOOR = 500

    /** Durée minimale au-dessus du plancher : 50 ms à 16 kHz (pas un clic). */
    private const val MIN_ABOVE_SAMPLES = 800

    /** Fantômes de transcription (normalisés, voir [isJunkTranscript]). */
    private val JUNK_PHANTOMS = setOf(
        "merci d'avoir regardé",
        "merci d'avoir regardé cette vidéo",
        "sous-titres réalisés par la communauté",
        "thanks for watching",
        "like and subscribe",
        "please subscribe",
        "abonnez-vous",
        "sous-titrage",
    )

    /**
     * Vrai si le PCM porte de la parole : au moins [MIN_ABOVE_SAMPLES]
     * échantillons ≥ [FLOOR] en valeur absolue. Vide ou quasi-silencieux :
     * faux — l'appelant termine l'écoute sans transcrire ni prévenir (rien
     * n'a été demandé).
     */
    fun hasSpeech(pcm: ByteArray): Boolean {
        var above = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            if (sample >= FLOOR || sample <= -FLOOR) {
                above += 1
                if (above >= MIN_ABOVE_SAMPLES) return true
            }
            i += 2
        }
        return false
    }

    /**
     * Vrai si la transcription ne vaut pas un appel LLM : vide, ponctuation
     * seule, moins de 2 lettres, ou fantôme connu des moteurs. Le reste part
     * — un mot réel, même court (« oui », « stop »), n'est jamais jeté ici.
     */
    fun isJunkTranscript(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 2) return true
        val flat = text.lowercase()
            .replace('’', '\'')
            .replace(Regex("[\\s.,!?:;\"«»]+"), " ")
            .trim()
        return JUNK_PHANTOMS.any { flat == it || flat.startsWith("$it ") || flat.endsWith(" $it") }
    }
}
