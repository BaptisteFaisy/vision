package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmAnswerTest {

    @Test
    fun `oui anglais et francais`() {
        assertEquals(ConfirmAnswer.YES, ConfirmAnswerParser.parse("yes"))
        assertEquals(ConfirmAnswer.YES, ConfirmAnswerParser.parse("YES!"))
        assertEquals(ConfirmAnswer.YES, ConfirmAnswerParser.parse("yeah"))
        assertEquals(ConfirmAnswer.YES, ConfirmAnswerParser.parse("yep"))
        assertEquals(ConfirmAnswer.YES, ConfirmAnswerParser.parse("oui"))
        assertEquals(ConfirmAnswer.YES, ConfirmAnswerParser.parse("  Oui. "))
    }

    @Test
    fun `non anglais et francais`() {
        assertEquals(ConfirmAnswer.NO, ConfirmAnswerParser.parse("no"))
        assertEquals(ConfirmAnswer.NO, ConfirmAnswerParser.parse("No."))
        assertEquals(ConfirmAnswer.NO, ConfirmAnswerParser.parse("nope"))
        assertEquals(ConfirmAnswer.NO, ConfirmAnswerParser.parse("non"))
        assertEquals(ConfirmAnswer.NO, ConfirmAnswerParser.parse("NON!"))
    }

    @Test
    fun `phrase entiere n est pas une reponse`() {
        assertNull(ConfirmAnswerParser.parse("yes please"))
        assertNull(ConfirmAnswerParser.parse("no thanks"))
        assertNull(ConfirmAnswerParser.parse("no, take me to Lyon"))
        assertNull(ConfirmAnswerParser.parse("oui je veux y aller"))
    }

    @Test
    fun `destination contenant oui ou non n est pas une reponse`() {
        assertNull(ConfirmAnswerParser.parse("Notre-Dame"))
        assertNull(ConfirmAnswerParser.parse("Noisy-le-Sec"))
        assertNull(ConfirmAnswerParser.parse("Lyon"))
        assertNull(ConfirmAnswerParser.parse(""))
        assertNull(ConfirmAnswerParser.parse("   "))
    }
}
