package dev.sam3vision.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un échec du relais doit se lire dans les Réglages, plus jamais dans le silence. */
class ShareSendMessageTest {

    @Test
    fun `un envoi accepte ou saute a la cadence ne dit rien`() {
        assertNull(shareSendMessage(ShareSend.Sent))
        assertNull(shareSendMessage(ShareSend.Skipped))
    }

    @Test
    fun `un refus 403 nomme la cle du flux`() {
        val message = shareSendMessage(ShareSend.Refused(403)).orEmpty()
        assertTrue(message, message.contains("key"))
        assertTrue(message, message.contains("stream"))
    }

    @Test
    fun `les autres refus portent leur code`() {
        assertEquals("The relay refused the send (code 503).", shareSendMessage(ShareSend.Refused(503)))
    }

    @Test
    fun `un relais injoignable parle de la connexion`() {
        val message = shareSendMessage(ShareSend.Unreachable).orEmpty()
        assertTrue(message, message.contains("unreachable"))
    }
}
