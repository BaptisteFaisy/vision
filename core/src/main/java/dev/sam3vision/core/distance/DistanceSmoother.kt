package dev.sam3vision.core.distance

import kotlin.math.abs

/**
 * Lissage des distances d'une frame à l'autre, par piste.
 *
 * À 1-2 images par seconde, une mesure brute saute de ±20 % : illisible. Une
 * moyenne mobile exponentielle suffit, avec deux vitesses — [alpha] pour suivre
 * un objet qui s'approche doucement, [jumpAlpha] quand la mesure fait un bond
 * (l'objet a vraiment changé de place, ou la piste a été reprise par un autre
 * objet) : inutile de traîner sur une valeur morte.
 *
 * Une piste muette depuis plus de [timeoutMs] est oubliée : l'objet qui revient
 * est mesuré à neuf, sans hériter de l'ancienne distance.
 */
class DistanceSmoother(
    private val alpha: Float = 0.35f,
    private val jumpAlpha: Float = 0.85f,
    private val jumpRatio: Float = 0.5f,
    private val timeoutMs: Long = 2_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class State(var meters: Float, var atMs: Long)

    private val states = HashMap<Int, State>()

    /** Nombre de pistes suivies (diagnostic et tests). */
    @Synchronized
    fun size(): Int = states.size

    /** Valeur lissée de [rawMeters] pour la piste [trackId] à l'instant [atMs]. */
    @Synchronized
    fun smooth(trackId: Int, rawMeters: Float, atMs: Long = clock()): Float {
        val previous = states[trackId]?.takeIf { atMs - it.atMs <= timeoutMs }
        val next = if (previous == null) rawMeters else blend(previous.meters, rawMeters)
        states[trackId] = State(next, atMs)
        trim(atMs)
        return next
    }

    /**
     * Dernière valeur lissée de [trackId] à [atMs], `null` si la piste est
     * inconnue ou périmée (même [timeoutMs] que l'oubli). Ne modifie rien :
     * relire n'est pas mesurer.
     */
    @Synchronized
    fun last(trackId: Int, atMs: Long = clock()): Float? {
        return states[trackId]?.takeIf { atMs - it.atMs <= timeoutMs }?.meters
    }

    /** Oublie toutes les pistes (nouvelle session). */
    @Synchronized
    fun reset() {
        states.clear()
    }

    private fun blend(previous: Float, raw: Float): Float {
        if (previous <= 0f) return raw
        val step = if (abs(raw - previous) / previous > jumpRatio) jumpAlpha else alpha
        return previous + step * (raw - previous)
    }

    private fun trim(atMs: Long) {
        states.entries.removeAll { atMs - it.value.atMs > timeoutMs }
    }
}