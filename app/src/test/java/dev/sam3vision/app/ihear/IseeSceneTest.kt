package dev.sam3vision.app.ihear

import dev.sam3vision.app.IHEAR_INTRO_TEXT
import dev.sam3vision.core.iseedesign.HEADING_MAX_LINES
import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.textPages
import dev.sam3vision.core.iseedesign.wrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La partie pure de l'écran iSee : le mapping état → scène (quel message, quel
 * libellé, quelle couleur, quelle scène), la pagination du gros texte et les règles
 * de visibilité des couches. Compose n'est pas testable ici (pas d'instrumentation),
 * donc tout ce qui décide de l'écran est extrait et vérifié sur JVM.
 */
class IseeSceneTest {

    private fun user(id: Long, text: String) = ChatEntry.UserBubble(id, text, null)

    private fun isee(id: Long, text: String) = ChatEntry.AiBubble(id, text, null)

    /** Un échange simple : la question du user puis la réponse d'iSee. */
    private fun exchange(
        question: String = "what is in front of me",
        answer: String = "a red chair and a wooden table",
    ) = IhearUiState(
        entries = listOf(user(1L, question), isee(2L, answer)),
        selected = 2,
    )

    @Test
    fun `un message du user s'affiche en YOU et en bleu`() {
        val state = exchange().copy(selected = 1) // la question du user
        val view = iseeSceneView(state, selectedEntry(state))
        assertEquals("YOU", view.label)
        assertTrue(view.fromUser)
        assertEquals("what is in front of me", view.heading)
    }

    @Test
    fun `une reponse d'iSee s'affiche en ISEE et en noir`() {
        val state = exchange()
        val view = iseeSceneView(state, selectedEntry(state))
        assertEquals("ISEE", view.label)
        assertFalse(view.fromUser)
        assertEquals("a red chair and a wooden table", view.heading)
    }

    @Test
    fun `un texte vide garde une page et ne casse rien`() {
        val state = IhearUiState(entries = listOf(isee(2L, "")), selected = 1)
        val view = iseeSceneView(state, selectedEntry(state))
        assertEquals("", view.heading)
        // Une page unique, vide : le gros texte sait s'afficher sans rien dire.
        assertEquals(listOf(listOf("")), textPages(view.heading))
        assertEquals("ISEE", view.label)
    }

    @Test
    fun `le gros texte se pagine par trois lignes avec jointure d'une ligne`() {
        val long = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen"
        val pages = textPages(long)
        assertTrue("plusieurs pages", pages.size > 1)
        pages.forEach { page -> assertTrue("3 lignes max", page.size <= HEADING_MAX_LINES) }
        // La dernière ligne d'une page est la première de la suivante : rien ne saute.
        pages.zipWithNext().forEach { (page, next) -> assertEquals(page.last(), next.first()) }
        assertEquals(wrap(long).first(), pages.first().first())
        assertEquals(wrap(long).last(), pages.last().last())
    }

    @Test
    fun `une selection hors de la liste ne montre aucune bulle`() {
        val state = exchange().copy(selected = 99)
        assertNull(selectedEntry(state))
        val view = iseeSceneView(state, selectedEntry(state))
        assertNull(view.label)
        assertEquals("", view.heading)
        assertEquals(IseeScene.READY, view.scene)
    }

    @Test
    fun `l'etat direct a priorite sur la bulle affichee`() {
        val state = exchange()
        assertEquals(IseeScene.VISION, iseeSceneView(state.copy(recordingVideo = true), user(1L, "q")).scene)
        assertEquals(IseeScene.VISION, iseeSceneView(state.copy(photoPath = "/tmp/p.jpg"), user(1L, "q")).scene)
        assertEquals(IseeScene.THINKING, iseeSceneView(state.copy(thinking = true), user(1L, "q")).scene)
        assertEquals(IseeScene.VOICE, iseeSceneView(state.copy(speakingEntryId = 2L), user(1L, "q")).scene)
        // L'écoute passe avant tout le reste : micro ouvert, on ne montre pas l'analyse.
        assertEquals(
            IseeScene.LISTENING,
            iseeSceneView(state.copy(holding = true, recordingVideo = true, thinking = true), user(1L, "q")).scene,
        )
        assertEquals(IseeScene.LISTENING, iseeSceneView(state.copy(transcribing = true), user(1L, "q")).scene)
    }

    @Test
    fun `la scene initiale porte le texte de l'intro`() {
        val state = IhearUiState()
        val entry = selectedEntry(state)
        assertTrue(entry is ChatEntry.IntroBubble)
        val view = iseeSceneView(state, entry)
        assertEquals("ISEE", view.label)
        assertFalse(view.fromUser)
        assertEquals(IHEAR_INTRO_TEXT, view.heading)
        // Mesuré, non voulu par le design : sans historique, l'intro est « la dernière
        // bulle », donc la mapping répond ANSWERED et non READY. READY n'est atteint que
        // sans entrée affichée (voir le cas « hors de la liste »). À trancher dans
        // IseeSceneState.kt, hors du périmètre de cette mission.
        assertEquals(IseeScene.ANSWERED, view.scene)
    }

    @Test
    fun `une bulle ancienne relue est une scene MEMORY`() {
        val state = exchange().copy(selected = 1)
        assertEquals(IseeScene.MEMORY, iseeSceneView(state, selectedEntry(state)).scene)
    }

    @Test
    fun `la carte camera remplace la bulle pendant VISION`() {
        val layers = iseeSceneLayers(IseeScene.VISION, bubbles = 2, dissolving = false)
        assertTrue(layers.cameraCard)
        assertFalse(layers.bubble)
        // Les dots sont réservés aux scènes de bulle.
        assertFalse(layers.pageDots)
    }

    @Test
    fun `la dissolution rend la bulle et retire la carte`() {
        val layers = iseeSceneLayers(IseeScene.VISION, bubbles = 2, dissolving = true)
        assertFalse(layers.cameraCard)
        assertTrue(layers.bubble)
    }

    @Test
    fun `les dots n'apparaissent qu'avec plusieurs bulles hors VISION`() {
        assertFalse(iseeSceneLayers(IseeScene.READY, bubbles = 1, dissolving = false).pageDots)
        assertTrue(iseeSceneLayers(IseeScene.READY, bubbles = 2, dissolving = false).pageDots)
        assertFalse(iseeSceneLayers(IseeScene.VISION, bubbles = 3, dissolving = false).pageDots)
    }
}