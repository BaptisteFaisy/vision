package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gravité du liquide de l'orbe : le verre tourne (spin), la surface du liquide
 * reste horizontale et ballottee. Verrouille les uniformes, le repère tournant
 * du verre, le repère fixe du liquide et la rotation écran du repli — même
 * philosophie textuelle que [OrbShaderTest] (pas de compilateur AGSL sur JVM).
 */
class OrbGravityTest {

    /** Le spin du verre et le ballottement sont des uniformes flottants. */
    @Test
    fun `le spin et le ballottement sont declares en uniformes`() {
        assertTrue(
            "uSpin n'est plus déclaré en uniform float",
            Regex("""uniform\s+float\s+uSpin\s*;""").containsMatchIn(ORB_AGSL),
        )
        assertTrue(
            "uSlosh n'est plus déclaré en uniform float",
            Regex("""uniform\s+float\s+uSlosh\s*;""").containsMatchIn(ORB_AGSL),
        )
    }

    /** Le verre a son repère tournant ; le liquide garde le repère écran. */
    @Test
    fun `le verre tourne dans son repere le liquide reste a l ecran`() {
        assertTrue(
            "le repère tournant du verre a disparu (rot(q, -uSpin))",
            ORB_AGSL.contains("float2 g = rot(q, -uSpin);"),
        )
        assertTrue(
            "le grain du verre ne tourne plus avec lui",
            ORB_AGSL.contains("float grain = fbm(g * 1.6"),
        )
        assertTrue(
            "le reflet chaud ne tourne plus avec le verre",
            ORB_AGSL.contains("float3(g, z)"),
        )
        assertTrue(
            "le liquide a quitté le repère écran (gravité perdue)",
            ORB_AGSL.contains("float2 p = q / 0.93;"),
        )
        assertTrue(
            "le masque du liquide ne compare plus p.y à la surface",
            ORB_AGSL.contains("p.y - surf"),
        )
    }

    /** Le ballottement ondule la surface, à zéro il ne change rien. */
    @Test
    fun `le ballottement ondule la surface quand il est arme`() {
        assertTrue(
            "l'onde de ballottement a disparu de la surface",
            ORB_AGSL.contains("uSlosh * 0.035 * sin(p.x * 4.5"),
        )
    }

    /** L'orbe au repos (accueil) ne tourne pas et ne ballotte pas. */
    @Test
    fun `l orbe au repos ne tourne pas`() {
        val look = OrbLook(time = 0f, you = 0f, see = 0f, amp = 0f, level = 0.5f)
        assertEquals(0f, look.spin, 0f)
        assertEquals(0f, look.slosh, 0f)
    }

    /** Rotation écran nulle : identité. */
    @Test
    fun `rotation nulle identite`() {
        val (x, y) = rotateXY(-0.3f, -0.35f, 0f)
        assertEquals(-0.3f, x, 1e-6f)
        assertEquals(-0.35f, y, 1e-6f)
    }

    /** Positive = horaire (comme `rotate()` de Compose) : le haut part à droite. */
    @Test
    fun `rotation 90 degres horaire`() {
        val (x, y) = rotateXY(0f, -1f, 90f)
        assertEquals(1f, x, 1e-5f)
        assertEquals(0f, y, 1e-5f)
    }

    /** Demi-tour : le reflet passe en bas à droite. */
    @Test
    fun `rotation 180 degres`() {
        val (x, y) = rotateXY(-0.42f, -0.48f, 180f)
        assertEquals(0.42f, x, 1e-5f)
        assertEquals(0.48f, y, 1e-5f)
    }

    /** Tour complet : on revient au point de départ (pas de saut). */
    @Test
    fun `rotation 360 degres identite`() {
        val (x, y) = rotateXY(-0.42f, -0.48f, 360f)
        assertEquals(-0.42f, x, 1e-5f)
        assertEquals(-0.48f, y, 1e-5f)
    }
}
