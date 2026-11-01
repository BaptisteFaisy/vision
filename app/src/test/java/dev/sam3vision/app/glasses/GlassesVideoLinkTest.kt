package dev.sam3vision.app.glasses

import dev.sam3vision.core.live.LinkDrop
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
    fun `le seq rendu est publie, puis oublie a l arret`() {
        GlassesVideoLink.setStreaming(true)
        GlassesVideoLink.push(frame())
        val seq = GlassesVideoLink.frames.value?.seq ?: 0L
        assertNull(GlassesVideoLink.renderedSeq.value)
        GlassesVideoLink.markRendered(seq)
        assertEquals(seq, GlassesVideoLink.renderedSeq.value)
        GlassesVideoLink.setStreaming(false)
        assertNull(GlassesVideoLink.renderedSeq.value)
    }

    @Test
    fun `un rendu sans seq allume live sans publier de seq`() {
        GlassesVideoLink.markRendered()
        assertTrue(GlassesVideoLink.live.value)
        assertNull(GlassesVideoLink.renderedSeq.value)
    }

    @Test
    fun `l horodatage d arrivee vaut 0 par defaut et ne change pas l identite`() {
        val plain = frame()
        assertEquals(0L, plain.arrivedAtNs)
        val stamped = plain.copy(arrivedAtNs = 123L)
        assertEquals(plain, stamped)
        assertEquals(plain.hashCode(), stamped.hashCode())
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

    @Test
    fun `les poussees config et degenerees comptent leurs drops sans mesure`() {
        GlassesVideoLink.push(frame().copy(codecConfig = true))
        GlassesVideoLink.push(frame().copy(width = 0))
        GlassesVideoLink.markPixel(atNs = 99L)
        val drops = GlassesVideoLink.linkDrops()
        assertEquals(1L, drops[LinkDrop.CODEC_CONFIG.ordinal])
        assertEquals(1L, drops[LinkDrop.DIMENSIONS.ordinal])
        assertEquals(0L, GlassesVideoLink.linkSamples)
    }

    @Test
    fun `deux poussees sans rendu confluent la premiere`() {
        GlassesVideoLink.push(dated(arrivedAtNs = 1_000_000_000L))
        GlassesVideoLink.push(dated(arrivedAtNs = 1_066_000_000L))
        val drops = GlassesVideoLink.linkDrops()
        assertEquals(1L, drops[LinkDrop.CONFLATED.ordinal])
        assertEquals(0L, GlassesVideoLink.linkSamples)
    }

    @Test
    fun `rendu puis pixel enregistrent la frame et son resume`() {
        val pushed = dated(arrivedAtNs = 1_000_000_000L)
        GlassesVideoLink.push(pushed)
        val seq = GlassesVideoLink.frames.value?.seq ?: 0L
        // Premier transit : jitter nul, dec 8421 µs, aff 7110 µs, perçu 15531 µs.
        GlassesVideoLink.noteRendered(seq, pushed, 1_010_000_000L, 1_018_421_000L)
        assertTrue(GlassesVideoLink.live.value)
        assertEquals(seq, GlassesVideoLink.renderedSeq.value)
        GlassesVideoLink.markPixel(atNs = 1_025_531_000L)
        assertEquals(1L, GlassesVideoLink.linkSamples)
        assertEquals(
            "GlassesLink e2e p50/p95/p99 15531/15531/15531 us (lien p95 0 us) drops 1+0",
            GlassesVideoLink.linkLine(),
        )
    }

    @Test
    fun `un rendu sans pixel est confle par le rendu suivant`() {
        val first = dated(arrivedAtNs = 1_000_000_000L)
        GlassesVideoLink.push(first)
        val seq = GlassesVideoLink.frames.value?.seq ?: 0L
        GlassesVideoLink.noteRendered(seq, first, 1_010_000_000L, 1_018_000_000L)
        val second = dated(arrivedAtNs = 1_066_000_000L)
        GlassesVideoLink.push(second)
        val seq2 = GlassesVideoLink.frames.value?.seq ?: 0L
        GlassesVideoLink.noteRendered(seq2, second, 1_076_000_000L, 1_084_000_000L)
        // Le premier rendu n'a jamais atteint le pixel : conflé à l'affichage.
        assertEquals(1L, GlassesVideoLink.linkDrops()[LinkDrop.CONFLATED.ordinal])
        assertEquals(0L, GlassesVideoLink.linkSamples)
        GlassesVideoLink.markPixel(atNs = 1_092_000_000L)
        assertEquals(1L, GlassesVideoLink.linkSamples)
    }

    @Test
    fun `une frame non datee allume le direct sans mesure inventee`() {
        GlassesVideoLink.push(frame())
        val seq = GlassesVideoLink.frames.value?.seq ?: 0L
        GlassesVideoLink.noteRendered(seq, frame(), 1_000L, 2_000L)
        GlassesVideoLink.markPixel(atNs = 3_000L)
        assertTrue(GlassesVideoLink.live.value)
        assertEquals(0L, GlassesVideoLink.linkSamples)
        assertEquals(0L, GlassesVideoLink.linkDrops().sum())
    }

    @Test
    fun `l arret du flux oublie la mesure du lien`() {
        val pushed = dated(arrivedAtNs = 1_000_000_000L)
        GlassesVideoLink.push(pushed)
        val seq = GlassesVideoLink.frames.value?.seq ?: 0L
        GlassesVideoLink.noteRendered(seq, pushed, 1_010_000_000L, 1_018_000_000L)
        GlassesVideoLink.markPixel(atNs = 1_026_000_000L)
        assertEquals(1L, GlassesVideoLink.linkSamples)
        GlassesVideoLink.setStreaming(false)
        assertEquals(0L, GlassesVideoLink.linkSamples)
        assertEquals(0L, GlassesVideoLink.linkDrops().sum())
        assertTrue(GlassesVideoLink.linkLine().endsWith("drops 0+0"))
    }

    private fun frame(bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        GlassesFrame(bytes, width = 504, height = 896, presentationTimeUs = 0L, compressed = true, codecConfig = false)

    /** Frame datée comme `pushCopy` : pts média + arrivée monotone téléphone. */
    private fun dated(arrivedAtNs: Long, presentationTimeUs: Long = 900_000L) =
        frame().copy(presentationTimeUs = presentationTimeUs, arrivedAtNs = arrivedAtNs)
}
