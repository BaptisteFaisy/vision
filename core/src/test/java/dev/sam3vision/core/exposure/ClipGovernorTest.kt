package dev.sam3vision.core.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gouverneur EV sur la boîte du feu : hystérésis (entrée 2 %, sortie 0,6 %),
 * stabilisation entre deux crans, bornes de la plage du téléphone et retour au repos
 * quand plus aucun feu n'est mesuré. Les fractions sont posées directement (le compteur
 * de pixels a son propre test, [ClippingTest]) pour que chaque assertion ne porte que
 * sur la décision.
 */
class ClipGovernorTest {

    private fun stats(fraction: Float): ClipStats = ClipStats(0, (fraction * 1000).toInt(), 1000)

    @Test
    fun `lampe crame - un cran vers le bas, puis stabilisation`() {
        val governor = ClipGovernor(minIndex = -6)
        assertEquals(-1, governor.tick(stats(0.05f), nowMs = 0, current = 0))
        // Même mesure 200 ms plus tard : l'AE n'a pas eu le temps de converger.
        assertNull(governor.tick(stats(0.05f), nowMs = 200, current = -1))
        // Après 700 ms, si c'est encore cramé, un cran de plus.
        assertEquals(-2, governor.tick(stats(0.05f), nowMs = 700, current = -1))
    }

    @Test
    fun `plage du telephone respectee - jamais sous minIndex`() {
        val governor = ClipGovernor(minIndex = -2)
        assertEquals(-1, governor.tick(stats(0.9f), 0, 0))
        assertEquals(-2, governor.tick(stats(0.9f), 700, -1))
        assertNull(governor.tick(stats(0.9f), 1400, -2))
    }

    @Test
    fun `profondeur bornee - deux crans meme si le telephone en offre six`() {
        val governor = ClipGovernor(minIndex = -6)
        assertEquals(-1, governor.tick(stats(0.5f), 0, 0))
        assertEquals(-2, governor.tick(stats(0.5f), 700, -1))
        // −6 EV est offert par le téléphone, mais la scène ne doit pas noircir : on s'arrête.
        assertNull(governor.tick(stats(0.5f), 1400, -2))
    }

    @Test
    fun `hysteresis - entre les deux seuils, on ne bouge pas`() {
        val governor = ClipGovernor(minIndex = -6)
        // 1 % : sous l'entrée (2 %), au-dessus de la sortie (0,6 %) → on garde le cran acquis.
        assertNull(governor.tick(stats(0.01f), nowMs = 0, current = -2))
    }

    @Test
    fun `crop redevenu sain - retour vers l'automatisme, jamais au-dessus de zero`() {
        val governor = ClipGovernor(minIndex = -6)
        assertEquals(-1, governor.tick(stats(0f), 0, -2))
        assertEquals(0, governor.tick(stats(0f), 700, -1))
        assertNull(governor.tick(stats(0f), 1400, 0))
    }

    @Test
    fun `plus aucune mesure - repos apres trois secondes de silence`() {
        val governor = ClipGovernor(minIndex = -6)
        assertEquals(-1, governor.tick(stats(0.4f), 0, 0))
        // La boîte disparaît : on ne descend plus, et on ne remonte pas tout de suite.
        assertNull(governor.tick(null, 800, -1))
        assertNull(governor.tick(null, 2000, -1))
        assertEquals(0, governor.tick(null, 3000, -1))
    }

    private fun stats(fraction: Float, median: Int): ClipStats =
        ClipStats(0, (fraction * 1000).toInt(), 1000, median)

    @Test
    fun `crop trop sombre - on remonte malgre l'ecretage, jamais au-dessus de zero`() {
        val governor = ClipGovernor(minIndex = -6)
        // Cramé ET sombre (médiane 20 < 51) : la remontée prime sur la descente.
        assertEquals(0, governor.tick(stats(0.5f, median = 20), 0, -1))
        // Déjà à 0 : plus rien à rendre, et surtout pas descendre pour assombrir encore.
        assertNull(governor.tick(stats(0.5f, median = 20), 700, 0))
    }

    @Test
    fun `crop sain et lumineux - descente normale`() {
        val governor = ClipGovernor(minIndex = -6)
        assertEquals(-1, governor.tick(stats(0.05f, median = 120), 0, 0))
        // Médiane juste au plancher : la règle ne tire pas (inégalité stricte).
        assertEquals(-2, governor.tick(stats(0.05f, median = ClipGovernor.DARK_MEDIAN), 700, -1))
    }

    @Test
    fun `mediane non mesuree - la regle de plancher reste inerte`() {
        // -1 = non mesurée : 1 % de blancs, entre sortie et entrée → l'hystérésis tient,
        // alors qu'une médiane à 0 aurait fait remonter à 0.
        assertNull(ClipGovernor(minIndex = -6).tick(ClipStats(0, 10, 1000), 700, -1))
    }

    @Test
    fun `sans plage exploitable - gouverneur inerte`() {
        assertNull(ClipGovernor(minIndex = 0).tick(stats(0.9f), 0, 0))
    }

    @Test
    fun `index hors plage - recadre sur la plage avant de decider`() {
        val governor = ClipGovernor(minIndex = -3)
        // Un index positif (impossible côté HAL) est ramené à 0 : pas de cran vers le haut.
        assertEquals(0, governor.tick(stats(0.001f), 0, 4))
    }
}
