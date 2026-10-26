package dev.sam3vision.core.usecase

/**
 * Sonde « Tester la voix » : une phrase très courte, la même partout, pour que
 * le coût du test reste négligeable devant le quota de caractères du compte.
 */
object SpeechProbe {
    const val TEXT = "SAM Vision, voice check."

    /** Verdict lisible d'un test de synthèse (vide = texte non joué, mais réseau bon). */
    fun verdict(bytes: Int): String = "Voix OK — %d octets MP3 reçus.".format(bytes)
}
