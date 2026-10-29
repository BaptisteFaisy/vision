package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveVoiceRequestTest {

    @Test
    fun `rien sans demande`() {
        assertNull(LiveVoiceRequest.consume())
    }

    @Test
    fun `demande lue une seule fois`() {
        LiveVoiceRequest.push(LiveVoiceRequest.Request(pictoHunt = "medicament", pictoDemo = true))
        val request = LiveVoiceRequest.consume()
        assertEquals("medicament", request?.pictoHunt)
        assertEquals(true, request?.pictoDemo)
        assertNull(request?.pictoCreate)
        assertNull(LiveVoiceRequest.consume())
    }

    @Test
    fun `la demande suivante ecrase la precedente`() {
        LiveVoiceRequest.push(LiveVoiceRequest.Request(pictoHunt = "ancien"))
        LiveVoiceRequest.push(LiveVoiceRequest.Request(pictoCreate = "medics"))
        val request = LiveVoiceRequest.consume()
        assertNull(request?.pictoHunt)
        assertEquals("medics", request?.pictoCreate)
        assertEquals(false, request?.pictoDemo)
    }
}
