package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.TwinLiteWord

/**
 * Repli 12 (SPEC v4) : quand rien ne parle et que rien n'a parlé depuis
 * 300 ms, dit le mot de guidage courant (gauche, droite, tout droit — GPS +
 * obstacles). Les positions ne passent jamais ici (niveaux 2 et 7) ; le mot
 * stop non plus (niveau 4, blocage). Pur hors du bus, testé en JVM.
 */
internal object FallbackVoice {
    /** Silence requis avant de parler (SPEC v4 : 300 ms). */
    const val SILENCE_MS = 300L

    /**
     * Vrai si [text] est un mot de guidage (virage ou tout-droit, SAM ou
     * TwinLite) : le seul parlé par le repli.
     */
    fun isGuidanceWord(text: String): Boolean {
        if (GuidanceWord.isStopPhrase(text)) return false
        return GuidanceWord.clipIdFor(text) != null || TwinLiteWord.clipIdFor(text) != null
    }

    /**
     * Propose [text] au repli : posté niveau 12 si mot de guidage et silence
     * de 300 ms à [nowMs]. Rend true si posté (le bus peut encore l'écraser).
     */
    fun offer(text: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (text.isNullOrBlank() || !isGuidanceWord(text)) return false
        if (VoiceBus.silenceMs(nowMs) < SILENCE_MS) return false
        VoiceBus.post(OutdoorLevel.FALLBACK, text, spokenClipId(text))
        return true
    }
}