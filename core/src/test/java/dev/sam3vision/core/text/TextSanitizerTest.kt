package dev.sam3vision.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Le nettoyeur doit retirer la mise en forme sans déformer le texte utile. */
class TextSanitizerTest {

    @Test
    fun `gras et italique disparaissent`() {
        assertEquals("Les verts dominent", stripMarkdown("**Les** *verts* dominent"))
        assertEquals("souligné", stripMarkdown("__souligné__"))
    }

    @Test
    fun `titres listes et liens sont aplatis`() {
        assertEquals("• un\n• deux", stripMarkdown("- un\n- deux"))
        assertEquals("Analyse\nok", stripMarkdown("## Analyse\nok"))
        assertEquals("voir https://x.dev", stripMarkdown("voir [https://x.dev]"))
    }

    @Test
    fun `backticks et paragraphes vides`() {
        assertEquals("couleurs", stripMarkdown("`couleurs`"))
        assertEquals("a\n\nb", stripMarkdown("a\n\n\n\nb"))
        assertFalse(stripMarkdown("rien").contains("*"))
    }

    @Test
    fun `texte deja propre n'est pas altere`() {
        val clean = "La scène montre deux piétons et une voiture jaune."
        assertEquals(clean, stripMarkdown(clean))
    }
}
