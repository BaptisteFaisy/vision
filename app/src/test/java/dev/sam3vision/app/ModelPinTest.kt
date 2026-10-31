package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde d'épinglage démo : le build ne change le modèle qu'en le disant, la clé n'est
 * « chauffée » qu'après un appel réel, et l'indicateur cite les noms exacts.
 */
class ModelPinTest {

    @Test
    fun `seul le defaut Meta est epingle`() {
        assertTrue(isPinnedLlmModel("meta-models/muse-spark-open-1026"))
        assertTrue(isPinnedLlmModel(Settings.DEFAULT_LLM_MODEL))
        assertTrue(!isPinnedLlmModel("ali/qwen3.8-omni-flash"))
        assertTrue(!isPinnedLlmModel(""))
    }

    @Test
    fun `pas d'avertissement quand rien ne vient du build`() {
        assertNull(modelOverrideWarning(Settings.Default, ""))
        assertNull(modelOverrideWarning(Settings.Default, Settings.DEFAULT_LLM_MODEL))
    }

    @Test
    fun `pas d'avertissement pour un choix saisi a la main`() {
        val manual = Settings.Default.copy(llmModel = "mon-modele")

        assertNull(modelOverrideWarning(manual, ""))
        assertNull(modelOverrideWarning(manual, "modele-du-build"))
    }

    @Test
    fun `le modele du build qui s'applique est signale`() {
        val fromBuild = Settings.Default.copy(llmModel = "modele-du-build")

        val warning = modelOverrideWarning(fromBuild, "modele-du-build")

        assertTrue(warning!!.contains("modele-du-build"))
        assertTrue(warning.contains(Settings.DEFAULT_LLM_MODEL))
    }

    @Test
    fun `la cle n'est chauffee qu'apres un appel reel OK`() {
        val noKey = Settings.Default.copy(llmApiKey = "")
        val keyed = Settings.Default.copy(llmApiKey = "une-cle")

        assertEquals("LLM key: missing", llmKeyStatus(noKey, null))
        assertEquals(
            "LLM key: entered, untested (“Test LLM”)",
            llmKeyStatus(keyed, null),
        )
        assertEquals(
            "LLM key: entered, untested (“Test LLM”)",
            llmKeyStatus(keyed, "Réglages enregistrés."),
        )
        assertEquals(
            "LLM key: warmed (last test OK)",
            llmKeyStatus(keyed, "LLM OK — « un mur vert »"),
        )
        assertEquals(
            "LLM key: failing (last test failed)",
            llmKeyStatus(keyed, "LLM failed — 401"),
        )
    }

    @Test
    fun `l'indicateur cite les noms exacts et les bases`() {
        val lines = modelPinLines(
            Settings.Default.copy(llmApiKey = "une-cle"),
            "",
            "LLM OK — « ok »",
        )

        val questions = lines.single { it.startsWith("questions:") }
        val direct = lines.single { it.startsWith("live:") }

        assertTrue(questions.contains("meta-models/muse-spark-open-1026"))
        assertTrue(questions.contains("https://inference.baseten.co/v1"))
        assertTrue(direct.contains("meta-models/muse-spark-open-1026"))
        assertTrue(direct.contains("https://inference.baseten.co/v1"))
        assertTrue(lines.any { it.contains("warmed") })
        assertTrue(lines.none { it.startsWith("⚠") })
    }

    @Test
    fun `la ligne debug cite les deux modeles`() {
        val line = debugModelsLine(Settings.Default.copy(liveLlmApiKey = "cle-live"))

        assertTrue(line.contains("meta-models/muse-spark-open-1026"))
        assertTrue(line.contains("live: meta-models/muse-spark-open-1026"))
        assertTrue(line.contains("key OK"))
        assertTrue(debugModelsLine(Settings.Default).contains("no key"))
    }
}
