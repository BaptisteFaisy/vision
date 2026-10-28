package dev.sam3vision.app.glasses

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Le pont vidéo lunettes → accueil : l'accueil n'ouvre la rue que sur du décodable avéré
 * (première frame rendue), et tout s'oublie à l'arrêt du flux. Objet partagé : chaque test
 * repart d'un pont vide.
 */
class GlassesVideoLinkTest {

    @Before
    @After
    fun resetLink() {
        GlassesVideoLink.detachNotes()
        GlassesVideoLink.clear()
    }

    @Test
    fun `le direct ne vit qu apres la premiere frame rendue`() {
        assertFalse(GlassesVideoLink.live.value)
        // Le flux seul prépare la surface (`streaming`) sans ouvrir la rue (`live`).
        GlassesVideoLink.setStreaming(true)
        assertTrue(GlassesVideoLink.streaming.value)
        assertFalse(GlassesVideoLink.live.value)
        GlassesVideoLink.markRendered()
        assertTrue(GlassesVideoLink.live.value)
    }

    @Test
    fun `l arret du flux oublie tout`() {
        GlassesVideoLink.setStreaming(true)
        GlassesVideoLink.push(frame())
        GlassesVideoLink.onVideoSize(504, 896)
        GlassesVideoLink.markRendered()
        GlassesVideoLink.setStreaming(false)
        assertFalse(GlassesVideoLink.streaming.value)
        assertFalse(GlassesVideoLink.live.value)
        assertNull(GlassesVideoLink.frames.value)
        assertNull(GlassesVideoLink.videoSize.value)
    }

    @Test
    fun `la derniere frame ecrase la precedente`() {
        GlassesVideoLink.push(frame(bytes = byteArrayOf(1)))
        GlassesVideoLink.push(frame(bytes = byteArrayOf(2)))
        assertEquals(byteArrayOf(2).toList(), GlassesVideoLink.frames.value?.frame?.bytes?.toList())
    }

    @Test
    fun `deux poussees identiques emettent deux fois`() {
        // Le mock rejoue la même image : sans compteur, le collecteur ne réessaierait jamais.
        val same = frame()
        GlassesVideoLink.push(same)
        val first = GlassesVideoLink.frames.value?.seq
        GlassesVideoLink.push(same)
        val second = GlassesVideoLink.frames.value?.seq
        assertEquals(first?.plus(1), second)
    }

    @Test
    fun `une taille nulle ou negative ne recadre rien`() {
        GlassesVideoLink.onVideoSize(0, 896)
        assertNull(GlassesVideoLink.videoSize.value)
        GlassesVideoLink.onVideoSize(504, -1)
        assertNull(GlassesVideoLink.videoSize.value)
        GlassesVideoLink.onVideoSize(504, 896)
        assertEquals(VideoSize(504, 896), GlassesVideoLink.videoSize.value)
    }

    @Test
    fun `l arret du flux garde le repli csd, raz l oublie`() {
        GlassesVideoLink.fallbackCsd = byteArrayOf(1, 2)
        GlassesVideoLink.setStreaming(true)
        GlassesVideoLink.setStreaming(false)
        assertEquals(listOf<Byte>(1, 2), GlassesVideoLink.fallbackCsd?.toList())
        GlassesVideoLink.clear()
        assertNull(GlassesVideoLink.fallbackCsd)
    }

    @Test
    fun `les notes vont au controleur, puis nulle part apres detachement`() {
        val received = mutableListOf<GlassesMessage>()
        GlassesVideoLink.attachNotes(received::add)
        val note = GlassesMessages.videoUndecodable()
        GlassesVideoLink.note(note)
        assertEquals(listOf(note), received)
        GlassesVideoLink.detachNotes()
        GlassesVideoLink.note(note)
        assertEquals(1, received.size)
    }

    private fun frame(bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        GlassesFrame(bytes, width = 504, height = 896, presentationTimeUs = 0L, compressed = true, codecConfig = false)
}
