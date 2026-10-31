package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Robustesse du planificateur [PeoplePath] : fuzz déterministe de scènes
 * aléatoires et entrées poubelle explicites.
 *
 * Invariants : jamais d'exception ; un cap annoncé passant tient l'écart
 * d'un demi-mètre à chaque personne (recalculé ici depuis le cap) ; même
 * entrée → même sortie ; une entrée poubelle rend `null` ou un résultat
 * fini et borné — jamais de NaN/Infini dans un résultat.
 */
class PeoplePathRobustnessTest {

    private companion object {
        /** Graines fixes : le fuzz est déterministe, rejouable à l'identique. */
        private val SEEDS = intArrayOf(1, 7, 42, 1234, 99991)

        /** Scènes par graine : 2 000 scènes, 4 000 plans, bien moins de 5 s. */
        private const val SCENES_PER_SEED = 400

        /** Tolérance sur l'écart : bord de bande + arrondis flottants. */
        private const val GAP_EPS = 0.005f

        /** Personnes : clés de PersonLabels + variantes (casse, accent). */
        private val PERSON_LABELS = arrayOf(
            "personne", "person", "personnes", "enfant", "homme", "femme",
            "child", "pedestrian", "Piéton", "PERSON",
        )

        /** Objets : jamais des personnes (isPerson faux). */
        private val OBJECT_LABELS = arrayOf(
            "camion", "chaise", "chien", "table", "voiture", "arbre", "mur", "poteau",
        )
    }

    @Test
    fun `fuzz scenes aleatoires invariants tenus`() {
        for (seed in SEEDS) {
            val random = Random(seed)
            for (scene in 0 until SCENES_PER_SEED) {
                val labels = sceneAleatoire(random)
                val contexte = "graine $seed scène $scene"
                val premier = planifieOuEchoue(labels, contexte)
                val second = planifieOuEchoue(labels, contexte)
                assertEquals("déterminisme $contexte", premier, second)
                val path = premier ?: continue
                assertResultatSain(path, contexte)
                if (path.fits) verifieEcarts(path, labels, contexte)
            }
        }
    }

    @Test
    fun `poubelle distance inconnue ou negative rend null`() {
        val poubelles: List<Float?> = listOf(
            null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            -2f, -1e30f, 0f,
        )
        for (meters in poubelles) {
            val labels = listOf(PlacedLabel("personne", 0.5f, 0.5f, meters))
            val contexte = "meters=$meters"
            assertNull(contexte, planifieOuEchoue(labels, contexte))
        }
    }

    @Test
    fun `poubelle distance extreme reste saine`() {
        // 1e30 : hors de portée → null ; 1e-30 : tout bouché → fits faux, borné.
        assertPoubelleSaine(listOf(PlacedLabel("personne", 0.5f, 0.5f, 1e30f)), "meters=1e30")
        assertPoubelleSaine(listOf(PlacedLabel("personne", 0.5f, 0.5f, 1e-30f)), "meters=1e-30")
    }

    @Test
    fun `poubelle centre invalide rend null`() {
        for (centerX in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val labels = listOf(PlacedLabel("personne", centerX, 0.5f, 2f))
            val contexte = "centerX=$centerX"
            assertNull(contexte, planifieOuEchoue(labels, contexte))
        }
    }

    @Test
    fun `poubelle centre extreme reste saine`() {
        for (centerX in listOf(-1e30f, -5f, 2f, 1e30f)) {
            val labels = listOf(PlacedLabel("personne", centerX, 0.5f, 2f))
            assertPoubelleSaine(labels, "centerX=$centerX")
        }
    }

    @Test
    fun `poubelle boites degenerees reste saine`() {
        val boites: List<Pair<Float?, Float?>> = listOf(
            Pair(null, null),
            0.6f to 0.4f,
            0.5f to 0.5f,
            Float.NaN to 0.6f,
            0.4f to Float.NaN,
            Float.NaN to Float.NaN,
            0.4f to null,
            null to 0.6f,
            Float.NEGATIVE_INFINITY to Float.POSITIVE_INFINITY,
            -1e30f to 1e30f,
        )
        for ((x0, x1) in boites) {
            val labels = listOf(PlacedLabel("personne", 0.5f, 0.5f, 2f, x0, x1))
            assertPoubelleSaine(labels, "boîte x0=$x0 x1=$x1")
        }
    }

    @Test
    fun `poubelle scene vide doublons et foule reste saine`() {
        assertNull("vide", planifieOuEchoue(emptyList(), "vide"))
        val doublons = listOf(
            PlacedLabel("personne", 0.5f, 0.5f, 2f),
            PlacedLabel("personne", 0.5f, 0.5f, 2f),
        )
        assertPoubelleSaine(doublons, "doublons")
        val foule = List(32) { i ->
            val label = if (i % 2 == 0) "personne" else "chaise"
            PlacedLabel(label, 0.05f + 0.9f * i / 31f, 0.5f, 1f + (i % 4))
        }
        assertPoubelleSaine(foule, "32 obstacles")
        assertEquals(planifieOuEchoue(foule, "foule bis"), planifieOuEchoue(foule, "foule ter"))
    }

    @Test
    fun `poubelle visee et champ extremes restent sains`() {
        val passant = listOf(PlacedLabel("personne", 0.5f, 0.5f, 2f))
        val visees = listOf(0f, 1f, -3f, 2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (aimX in visees) {
            assertPoubelleSaine(passant, aimX, LensSpec.DEFAULT_H_FOV_DEG, "aimX=$aimX")
        }
        val champs = listOf(0f, 1f, -45f, 200f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (hfovDeg in champs) {
            assertPoubelleSaine(passant, 0.5f, hfovDeg, "hfovDeg=$hfovDeg")
        }
    }

    @Test
    fun `visee NaN refuse le plan`() {
        val passant = listOf(PlacedLabel("personne", 0.5f, 0.5f, 2f))

        assertNull(PeoplePath.plan(passant, aimX = Float.NaN))
    }

    @Test
    fun `champ NaN refuse le plan`() {
        val mur = listOf(
            PlacedLabel("personne", 0.3f, 0.5f, 2f),
            PlacedLabel("personne", 0.5f, 0.5f, 2f),
            PlacedLabel("personne", 0.7f, 0.5f, 2f),
        )

        assertNull(PeoplePath.plan(mur, hfovDeg = Float.NaN))
    }

    @Test
    fun `bande non finie refuse le plan`() {
        val labels = listOf(
            PlacedLabel("personne", 0.5f, 0.5f, 2e38f, 0f, 1f),
            PlacedLabel("personne", 0.05f, 0.5f, 2f),
            PlacedLabel("personne", 0.95f, 0.5f, 2f),
        )

        assertNull(PeoplePath.plan(labels, rangeMeters = 3e38f, aimX = 0.05f))
    }

    /** Scène aléatoire : 1 à 8 obstacles, personnes et objets, boîtes variées. */
    private fun sceneAleatoire(random: Random): List<PlacedLabel> {
        val count = 1 + random.nextInt(8)
        val labels = ArrayList<PlacedLabel>(count)
        repeat(count) {
            val pool = if (random.nextFloat() < 0.6f) PERSON_LABELS else OBJECT_LABELS
            val label = pool[random.nextInt(pool.size)]
            val centerX = random.nextFloat() * 2f - 0.5f
            val meters = 0.3f + random.nextFloat() * 5.7f
            labels.add(avecBoiteOuNon(random, label, centerX, meters))
        }
        return labels
    }

    /** Label avec boîte (3 fois sur 4) ou sans : le plan ne devine jamais. */
    private fun avecBoiteOuNon(random: Random, label: String, centerX: Float, meters: Float): PlacedLabel {
        if (random.nextFloat() < 0.25f) return PlacedLabel(label, centerX, 0.5f, meters)
        val width = 0.02f + random.nextFloat() * 0.38f
        val x0 = centerX - width / 2f
        return PlacedLabel(label, centerX, 0.5f, meters, x0, x0 + width)
    }

    /** Plan sans surprise : une exception devient un échec nommé (graine, scène). */
    private fun planifieOuEchoue(labels: List<PlacedLabel>, contexte: String): PeoplePath? {
        try {
            return PeoplePath.plan(labels)
        } catch (e: Exception) {
            fail("exception $contexte : $e")
            return null // Inatteignable : fail lève toujours.
        }
    }

    /** Résultat fini et borné : cap dans l'image, écart fini positif, côté cohérent. */
    private fun assertResultatSain(path: PeoplePath, contexte: String) {
        assertTrue("$contexte : cap ${path.headingX}", path.headingX.isFinite())
        assertTrue(
            "$contexte : cap ${path.headingX} borné",
            path.headingX >= PeoplePath.EDGE_X && path.headingX <= 1f - PeoplePath.EDGE_X,
        )
        assertTrue("$contexte : écart ${path.clearanceM}", path.clearanceM.isFinite() && path.clearanceM >= 0f)
        val cote = if (path.headingX < 0.5f) GuidanceSide.Left else GuidanceSide.Right
        assertEquals("$contexte : côté", cote, path.side)
    }

    /** Poubelle : `null` ou résultat sain — jamais d'exception ni de NaN/Infini. */
    private fun assertPoubelleSaine(labels: List<PlacedLabel>, contexte: String) {
        val path = planifieOuEchoue(labels, contexte) ?: return
        assertResultatSain(path, contexte)
    }

    /** Poubelle avec visée et champ forcés : `null` ou résultat sain. */
    private fun assertPoubelleSaine(labels: List<PlacedLabel>, aimX: Float, hfovDeg: Float, contexte: String) {
        val path = try {
            PeoplePath.plan(labels, hfovDeg = hfovDeg, aimX = aimX)
        } catch (e: Exception) {
            fail("exception $contexte : $e")
            return
        } ?: return
        assertResultatSain(path, contexte)
    }

    /**
     * Écart recalculé depuis le cap : chaque personne à portée tient son
     * demi-mètre, chaque objet son quart de mètre — à GAP_EPS près. Seuls les
     * obstacles à portée comptent : le plan ignore les autres.
     */
    private fun verifieEcarts(path: PeoplePath, labels: List<PlacedLabel>, contexte: String) {
        val halfTangent = LensSpec.halfTangent(LensSpec.DEFAULT_H_FOV_DEG)
        for (label in labels) {
            val depth = label.meters
            if (depth == null || !depth.isFinite() || depth <= 0f || depth > Guidance.RANGE_METERS) continue
            val attendu = if (PersonLabels.isPerson(label.label)) {
                PeoplePath.MIN_CLEARANCE_M
            } else {
                Guidance.RAY_HALF_WIDTH_M
            }
            val ecart = ecartM(path.headingX, label, depth, halfTangent)
            assertTrue("$contexte : écart ${label.label} = $ecart", ecart + GAP_EPS >= attendu)
        }
    }

    /**
     * Écart latéral en mètres entre le cap et le corps (ou le bord mesuré) :
     * distance angulaire projetée en mètres moins la demi-largeur du corps —
     * la définition du contrat, pas le calcul interne des bandes.
     */
    private fun ecartM(headingX: Float, label: PlacedLabel, depth: Float, halfTangent: Float): Float {
        val lateralPerX = 2f * depth * halfTangent
        val x0 = label.x0
        val x1 = label.x1
        val demieBoite = if (x0 != null && x1 != null && x0.isFinite() && x1.isFinite() && x0 != x1) {
            abs(x1 - x0) * 0.5f * lateralPerX
        } else {
            null
        }
        val corps = demieBoite ?: if (PersonLabels.isPerson(label.label)) {
            PeoplePath.BODY_HALF_WIDTH_M
        } else {
            0f
        }
        return abs(headingX - label.centerX) * lateralPerX - corps
    }
}
