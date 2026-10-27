package dev.sam3vision.app.isee

import dev.sam3vision.app.ISEE_INTRO_TEXT
import dev.sam3vision.app.isee.preview.IseePreviewFixtures
import dev.sam3vision.app.isee.preview.IseePreviewStates
import dev.sam3vision.core.iseedesign.BubbleLook
import dev.sam3vision.core.iseedesign.DISSOLVE_COLS
import dev.sam3vision.core.iseedesign.DISSOLVE_ROWS
import dev.sam3vision.core.iseedesign.DissolvePlan
import dev.sam3vision.core.iseedesign.HEADING_MAX_LINES
import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.bubbleLookIndex
import dev.sam3vision.core.iseedesign.bubbleMorph
import dev.sam3vision.core.iseedesign.dissolvePlan
import dev.sam3vision.core.iseedesign.iSeeGeometry
import dev.sam3vision.core.iseedesign.satelliteAlpha
import dev.sam3vision.core.iseedesign.satellitePositions
import dev.sam3vision.core.iseedesign.textPages
import dev.sam3vision.core.iseedesign.worldAlpha
import dev.sam3vision.core.iseedesign.wrap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Le banc de rendu iSee, côté JVM : Compose ne se rend pas sans appareil, donc ce
 * fichier vérifie tout ce que les previews **consomment** et qui est mesurable —
 * la géométrie, le plan de dissolution, les apparences, la pagination et la
 * correspondance état → scène. Ce qui dépend du moteur Compose (position réelle des
 * glyphes, antialiasing, ombres) reste hors de portée : c'est dit dans la PR.
 */
class IseeRenderTest {

    private val geom = iSeeGeometry(1080f, 2400f)
    private val plan = dissolvePlan(geom)

    /** Nombre de cellules dont la tache est déjà posée dans la bulle à l'instant t. */
    private fun painted(p: DissolvePlan, t: Float): Int = p.paths.count { worldAlpha(it, t) > 0f }

    @Test
    fun `la grille de capture et celle de la dissolution sont la meme 26 x 16`() {
        assertEquals(26, GRID_COLS)
        assertEquals(16, GRID_ROWS)
        assertEquals(26, DISSOLVE_COLS)
        assertEquals(16, DISSOLVE_ROWS)
        // La grille de la carte et le plan volent ensemble : l'overlay dessine
        // minOf(colors.size, plan.size()) cellules, les deux doivent coïncider.
        assertEquals(GRID_COLS * GRID_ROWS, DISSOLVE_COLS * DISSOLVE_ROWS)
    }

    @Test
    fun `la grille du pane fait 416 cellules pretes et deterministes`() {
        val a = IseePreviewFixtures.grid()
        val b = IseePreviewFixtures.grid()
        assertEquals(GRID_COLS * GRID_ROWS, a.colors.size)
        assertEquals(416, a.colors.size)
        assertEquals(GRID_COLS, a.cols)
        assertEquals(GRID_ROWS, a.rows)
        assertTrue("grille prête dès le premier échantillon", a.ready)
        // Déterminisme : la même image donne exactement la même grille.
        assertArrayEquals(a.colors, b.colors)
    }

    @Test
    fun `sans bitmap ni photo la grille reste vide et non prete`() {
        val preview = samplePreviewGrid(null)
        assertTrue(preview.colors.isEmpty())
        assertFalse(preview.ready)
        assertEquals(GRID_COLS, preview.cols)
        val photo = runBlocking { samplePhotoGrid(null) }
        assertTrue(photo.colors.isEmpty())
        assertFalse(photo.ready)
    }

    @Test
    fun `le plan de dissolution couvre 26 x 16 cellules sur toute la duree`() {
        assertEquals(416, plan.size())
        for (p in plan.paths) {
            assertTrue("detach hors bornes : ${p.detach}", p.detach in 0f..0.55f)
            assertTrue("arrive <= detach : ${p.arrive} / ${p.detach}", p.arrive > p.detach)
            assertTrue("arrive trop tard : ${p.arrive}", p.arrive <= 0.99f)
        }
        // La vague de dessaisissement est désynchronisée : chaque cellule a son instant.
        assertTrue("timings tous identiques", plan.paths.map { it.detach }.distinct().size > 100)
    }

    @Test
    fun `le plan est deterministe pour une meme geometrie`() {
        val other = dissolvePlan(geom)
        assertEquals(plan.size(), other.size())
        for (i in 0 until plan.size()) {
            assertEquals(plan.paths[i].detach, other.paths[i].detach, 0f)
            assertEquals(plan.paths[i].arrive, other.paths[i].arrive, 0f)
            assertEquals(plan.paths[i].endU, other.paths[i].endU, 0f)
            assertEquals(plan.paths[i].burstY, other.paths[i].burstY, 0f)
        }
    }

    @Test
    fun `les trois instants des previews sont visiblement differents`() {
        // t=0 : carte entière, aucune tache posée dans la bulle.
        assertEquals(0, painted(plan, 0f))
        // t=1 : toutes les taches sont posées (la gerbe est atterrie).
        assertEquals(416, painted(plan, 1f))
        // t=0,5 : la moitié du damier a déjà plongé — ni 0 ni 416.
        val mid = painted(plan, 0.5f)
        assertTrue("t=0,5 doit peindre entre les deux : $mid", mid in 1 until 416)
        // La bulle grandit de son ancre jusqu'à sa pose finale sur le même temps.
        assertEquals(0f, bubbleMorph(0f), 0f)
        assertEquals(1f, bubbleMorph(1f), 0f)
        assertTrue(bubbleMorph(0.3f) < bubbleMorph(0.6f))
        // Les satellites sont cueillis pendant la dissolution, pleins une fois posée.
        assertEquals(0f, satelliteAlpha(bubbleMorph(0f)), 0f)
        assertEquals(1f, satelliteAlpha(bubbleMorph(1f)), 0f)
    }

    @Test
    fun `six satellites, tous distincts, tous autour du disque`() {
        val spots = satellitePositions(SATELLITE_COUNT)
        assertEquals(6, SATELLITE_COUNT)
        assertEquals(SATELLITE_COUNT, spots.size)
        assertEquals("deux satellites au même endroit", SATELLITE_COUNT, spots.distinct().size)
        for ((u, v) in spots) {
            val d = hypot(u.toDouble(), v.toDouble())
            assertTrue("satellite collé au centre ou hors cadre : $u,$v", d in 0.5..1.15)
        }
    }

    @Test
    fun `les 16 apparences sont distinctes, stables et toutes atteignables`() {
        val looks = BubbleLook.values()
        assertEquals(16, looks.size)
        val specs = looks.map { specOf(it) }
        // Aucune entrée de table ne sert deux looks : 16 instances, 16 apparences.
        for (i in specs.indices) {
            for (j in i + 1 until specs.size) assertTrue("looks $i et $j partagent leur spec", specs[i] !== specs[j])
        }
        // Le tirage est stable pour un même identifiant…
        for (id in listOf(INTRO_BUBBLE_ID, 0L, 1L, 42L, 123_456_789L)) {
            assertEquals(bubbleLookIndex(id), bubbleLookIndex(id))
            assertSame(specOf(looks[bubbleLookIndex(id)]), specOf(looks[bubbleLookIndex(id)]))
        }
        // … et les 16 apparences sont effectivement servies par des identifiants réels.
        val reachable = (1L..64L).map { bubbleLookIndex(it) }.toSet()
        assertEquals(looks.indices.toSet(), reachable)
    }

    @Test
    fun `chaque motif de bulle de la table sert au moins une fois`() {
        val patterns = BubbleLook.values().map { specOf(it).pattern }.toSet()
        assertEquals("motifs déclarés : ${Pattern.values().size}", Pattern.values().size, patterns.size)
        assertEquals(Pattern.values().toSet(), patterns)
    }

    @Test
    fun `le gros texte se pagine a 19 caracteres, 3 lignes, avec recouvrement`() {
        val text = ISEE_INTRO_TEXT
        val lines = wrap(text)
        assertTrue("ligne trop longue : ${lines.maxOf { it.length }}", lines.all { it.length <= 19 })
        assertEquals("découpe non déterministe", lines, wrap(text))
        val pages = textPages(text)
        assertTrue("plus de 3 lignes par page", pages.all { it.size <= HEADING_MAX_LINES })
        assertEquals(HEADING_MAX_LINES, pages.first().size)
        for (i in 0 until pages.size - 1) {
            // Le recouvrement garde la ligne de lecture : la dernière d'une page est
            // la première de la suivante, sur toute la hauteur du texte.
            assertEquals("jointure des pages $i/${i + 1}", pages[i].last(), pages[i + 1].first())
        }
        assertEquals("pagination non déterministe", pages, textPages(text))
        assertTrue("3 pages minimum pour l'intro", pages.size >= 3)
    }

    @Test
    fun `les etats de preview tombent sur les scenes du design`() {
        val expected: List<Triple<IseeUiState, IseeScene, String?>> = listOf(
            // état, scène, libellé au-dessus du gros texte (le bleu suit « YOU »)
            Triple(IseePreviewStates.intro, IseeScene.ANSWERED, "ISEE"),
            Triple(IseePreviewStates.userMessage, IseeScene.READY, "YOU"),
            Triple(IseePreviewStates.answered, IseeScene.ANSWERED, "ISEE"),
            Triple(IseePreviewStates.memory, IseeScene.MEMORY, "YOU"),
            Triple(IseePreviewStates.listening, IseeScene.LISTENING, "ISEE"),
            Triple(IseePreviewStates.voice, IseeScene.VOICE, "ISEE"),
            Triple(IseePreviewStates.blank, IseeScene.READY, null),
        )
        for ((state, scene, label) in expected) {
            val view = iseeSceneView(state, selectedEntry(state))
            assertEquals("scène de $state", scene, view.scene)
            assertEquals("libellé de $state", label, view.label)
            // Le bleu du gros texte ne suit que le message du user ; l'intro, posée
            // comme première bulle, prend le libellé et la couleur de iSee.
            assertEquals("couleur bleue de $state", label == "YOU", view.fromUser)
        }
        // Les previews ne couvrent pas que des cas vides : tous les états diffèrent.
        assertEquals(expected.size, IseePreviewStates.variants.size)
        assertEquals(expected.size, IseePreviewStates.variants.distinct().size)
    }

    @Test
    fun `l'intro seule affiche le texte de l'intro, la scene vide n'affiche rien`() {
        val intro = iseeSceneView(IseePreviewStates.intro, selectedEntry(IseePreviewStates.intro))
        assertEquals(ISEE_INTRO_TEXT, intro.heading)
        val blank = iseeSceneView(IseePreviewStates.blank, selectedEntry(IseePreviewStates.blank))
        assertEquals("", blank.heading)
        assertEquals(null, blank.label)
    }
}