package dev.sam3vision.core.nav

/**
 * Détour obstacle subi : la sonde SAM ne suit plus le cap (couloir bouché,
 * retour trottoir) depuis [sustainMs] sans discontinuer → il faut recalculer
 * l'itinéraire Valhalla depuis la position GPS temps réel. Le meilleur chemin
 * tient compte des obstacles : le local contourne, le global replanifie
 * (ordre user du 29/10 — GPS + obstacles fusionnés, une seule voix).
 *
 * Feu unique par épisode ([cooldownMs] entre deux feux) : un recalcul par
 * minute au plus, même si le détour dure. Pur et testé en JVM.
 */
class DetourReroute(
    private val sustainMs: Long = SUSTAIN_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    /** Début du détour subi en cours, `null` hors détour. */
    private var detourSinceMs: Long? = null

    /** Dernier feu, [NO_INSTANT] si aucun. */
    private var lastFireMs: Long = NO_INSTANT

    /**
     * Détour subi à [nowMs] : vrai une fois, quand il dure depuis [sustainMs]
     * hors cooldown. Un retour au cap remet le compteur à zéro.
     */
    fun onDetour(detour: Boolean, nowMs: Long): Boolean {
        if (!detour) {
            detourSinceMs = null
            return false
        }
        val since = detourSinceMs ?: nowMs.also { detourSinceMs = it }
        if (nowMs - since < sustainMs) return false
        if (lastFireMs != NO_INSTANT && nowMs - lastFireMs < cooldownMs) return false
        lastFireMs = nowMs
        return true
    }

    /** Oublie le détour en cours et le dernier feu (nouveau guidage). */
    fun reset() {
        detourSinceMs = null
        lastFireMs = NO_INSTANT
    }

    companion object {
        /** Détour subi continu avant de recalculer (un contournement prend des secondes). */
        const val SUSTAIN_MS = 8_000L

        /** Silence entre deux recalculs (un itinéraire par minute au plus). */
        const val COOLDOWN_MS = 60_000L

        /** Aucun feu encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
