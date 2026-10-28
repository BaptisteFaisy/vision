package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille la source AGSL de l'orbe contre le plantage « index expression must be
 * constant » : AGSL/SkSL n'accepte que des indices constants sur les uniformes tableaux,
 * et un index dynamique (`uIdle[i]`) fait échouer `RuntimeShader(…)` au premier affichage
 * de l'orbe. Toute retouche du shader qui réintroduit un index non constant échoue ici,
 * sur la JVM, au lieu de planter sur l'appareil.
 *
 * Limites (choix assumé) : ce test est purement textuel — il n'existe pas de compilateur
 * AGSL sur la JVM, donc il ne prouve pas que le shader compile, seulement que chaque
 * subscript est un littéral 0..4. Il est volontairement plus strict que le langage (une
 * expression constante non littérale échouerait) : fail-closed, le shader actuel n'utilise
 * que des littéraux.
 */
class OrbShaderTest {

    /** Les trois palettes restent des tableaux de 5 couleurs, comme les tableaux envoyés. */
    @Test
    fun `les palettes sont declarees en tableaux de 5`() {
        listOf("uIdle", "uPalYou", "uPalSee").forEach { name ->
            assertTrue(
                "$name n'est plus déclaré en half4[5]",
                Regex("""uniform\s+half4\s+$name\s*\[\s*5\s*]\s*;""").containsMatchIn(ORB_AGSL),
            )
        }
    }

    /** Chaque subscript des palettes est un littéral 0..4 : 3 palettes × 5 teintes. */
    @Test
    fun `les subscripts des palettes sont des litteraux 0 a 4`() {
        val indices = subscriptsDesPalettes(corpsDuShader())
        assertEquals("le test ne verrouille plus les 15 subscripts", 15, indices.size)
        indices.forEach { index ->
            assertTrue(
                "index « $index » non constant : AGSL exige un littéral (replanterait l'orbe)",
                index.matches(Regex("[0-4]")),
            )
        }
    }

    /** Filet : aucun autre crochet (nouvel uniforme tableau, boucle…) hors déclarations. */
    @Test
    fun `aucun autre crochet ne subsiste dans le corps du shader`() {
        val sansPalettes = corpsDuShader().replace(PALETTE_SUBSCRIPT, "")
        assertTrue(
            "crochet inattendu dans le shader, étendre ce test avant de l'autoriser",
            '[' !in sansPalettes && ']' !in sansPalettes,
        )
    }

    private fun corpsDuShader(): String = ORB_AGSL.lineSequence()
        .map { it.substringBefore("//") }
        .filter { !it.trimStart().startsWith("uniform ") }
        .joinToString("\n")

    private fun subscriptsDesPalettes(corps: String): List<String> =
        PALETTE_SUBSCRIPT.findAll(corps).map { it.groupValues[1].trim() }.toList()

    private companion object {
        val PALETTE_SUBSCRIPT = Regex("""u(?:Idle|PalYou|PalSee)\s*\[([^\]]*)]""")
    }
}
