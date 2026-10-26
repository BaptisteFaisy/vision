package dev.sam3vision.core.iseedesign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DissolveTest {

    private val geom = iSeeGeometry(1080f, 2400f)
    private val plan = dissolvePlan(geom)

    @Test
    fun `geometrie - la bulle finit centree, pleine, en dedans de l'ecran`() {
        val fin = iSeeGeometry(1080f, 2400f, morph = 1f)
        assertEquals(540f, fin.bubbleCx, 1e-3f)
        assertEquals(912f, fin.bubbleCy, 1e-1f)
        assertEquals(361.8f, fin.bubbleR, 1e-1f)
        // Le disque reste en dedans de l'écran, et la carte au-dessus de la bulle.
        assertTrue(fin.bubbleCy - fin.bubbleR > 0f)
        assertTrue(fin.bubbleCy + fin.bubbleR < fin.height)
        assertTrue(fin.cardCy + fin.cardH * 0.5f > fin.bubbleCy - fin.bubbleR)
    }

    @Test
    fun `morph 0 - l'ancre de capture est sous la carte, petite a droite`() {
        val d = iSeeGeometry(1080f, 2400f, morph = 0f)
        assertTrue(d.bubbleCx > d.width * 0.6f)
        assertTrue(d.bubbleR < d.width * 0.25f)
        assertTrue(d.satelliteR < d.bubbleR)
        // Le morph pousse la bulle vers plus grand et plus centré.
        assertTrue(iSeeGeometry(1080f, 2400f, morph = 1f).bubbleR > d.bubbleR)
        assertTrue(iSeeGeometry(1080f, 2400f, morph = 1f).bubbleCx < d.bubbleCx)
    }

    @Test
    fun `plan - chaque cellule quitte la carte, vole, et atterrit`() {
        assertEquals(CARD_COLS * CARD_ROWS, plan.size())
        for (i in 0 until plan.size()) {
            val p = plan.paths[i]
            assertTrue(p.detach >= 0f && p.detach <= 0.55f)
            assertTrue(p.arrive > p.detach && p.arrive <= 1f)
            // Départ dans la carte, atterrissage dans le disque au globe.
            assertTrue(abs(p.cellX - geom.cardCx) <= geom.cardW * 0.5f)
            assertTrue(abs(p.cellY - geom.cardCy) <= geom.cardH * 0.5f)
            assertTrue(abs(p.endU) <= 0.95f && abs(p.endV) <= 0.95f)
            // La colonne donne l'axe du globe : toutes les cellules d'un même
            // index de colonne atterrissent sur la même horizontale.
            val colRef = ((i % CARD_COLS) + 0.5f) / CARD_COLS * 2f - 1f
            assertEquals(colRef * 0.92f, p.endU, 1e-4f)
        }
    }

    @Test
    fun `pixelStage - invisible avant le detach et apres l'atterrissage`() {
        val p = plan.paths[0]
        if (p.detach > 0f) assertNull(pixelStage(p, geom, t = 0f))
        assertNull(pixelStage(p, geom, t = p.arrive))
        assertNull(pixelStage(p, geom, t = p.detach))
    }

    @Test
    fun `pixelStage - en vol, l'opacite monte vite puis la taille s'affine`() {
        // Un pixel dont le vol couvre la fin du plan (la plupart sont couverts).
        var vu = false
        for (i in 0 until plan.size()) {
            val p = plan.paths[i]
            val mid = (p.detach + p.arrive) * 0.5f
            val stage = pixelStage(p, geom, mid) ?: continue
            vu = true
            assertTrue(stage.alpha > 0.5f)
            assertTrue(stage.size > 0f && stage.size <= 2f)
            // Au milieu du vol, le pixel est entre la carte et la porte de la bulle.
            assertTrue(stage.y > 0f && stage.y < geom.height)
        }
        assertTrue(vu)
    }

    @Test
    fun `pixelStage - le depart s'envole, l'arrivee plonge dans le disque`() {
        var checked = 0
        for (i in 0 until plan.size()) {
            val p = plan.paths[i]
            val a = pixelStage(p, geom, p.detach + 0.001f) ?: continue
            val b = pixelStage(p, geom, p.arrive - 0.001f) ?: continue
            checked++
            // Le départ est près de sa cellule, l'arrivée près de sa tache.
            val ax = geom.bubbleCx + p.endU * geom.bubbleR
            val ay = geom.bubbleCy + p.endV * geom.bubbleR
            assertTrue(abs(a.x - p.cellX) < geom.cardW * 0.1f)
            assertTrue(abs(b.x - ax) < geom.bubbleR * 0.2f)
            assertTrue(abs(b.y - ay) < geom.bubbleR * 0.2f)
        }
        assertTrue(checked > plan.size() / 2)
    }

    @Test
    fun `determinisme - le meme index donne le meme trajet`() {
        val p1 = dissolvePlan(geom)
        for (i in 0 until plan.size()) {
            assertEquals(plan.paths[i].detach, p1.paths[i].detach, 1e-6f)
            assertEquals(plan.paths[i].endU, p1.paths[i].endU, 1e-6f)
        }
    }

    @Test
    fun `monde - la tache apparait sur l'atterrissage puis reste`() {
        var i = 0
        while (i < plan.size()) {
            val p = plan.paths[i]
            val pop = worldAlpha(p, t = p.arrive + 0.05f)
            val full = worldAlpha(p, t = 1f + 1f)
            if (pop in 0f..1f) {
                assertTrue(pop > 0f && full > pop)
                assertTrue(full <= 1f)
            }
            i++
        }
        // À la fin du plan, toutes les taches sont pleines.
        for (p in plan.paths) assertEquals(1f, worldAlpha(p, t = 10f), 1e-6f)
    }

    @Test
    fun `eclat - glint attenué la couleur vers le clair, jamais plus que demi`() {
        val sombre = 0xFF142848.toInt()
        val lit = glint(sombre, 1f)
        val r = (lit ushr 16) and 0xFF
        val b = lit and 0xFF
        assertTrue(r > 0x14)
        assertTrue(r <= (0x14 + (255 - 0x14) * 0.55f).toInt() + 2)
        assertTrue(b > 0x48)
        assertEquals(sombre, glint(sombre, 0f))
    }

    @Test
    fun `ticks - longueurs variees mais bornées, et la phase anime`() {
        for (i in 0 until 26) {
            val l = tickLength(i, 0f)
            assertTrue(l > 0f && l < 0.4f)
        }
        val a = tickLength(3, 0f)
        val b = tickLength(3, 0.5f)
        assertTrue(abs(a - b) > 1e-3f)
    }
}
