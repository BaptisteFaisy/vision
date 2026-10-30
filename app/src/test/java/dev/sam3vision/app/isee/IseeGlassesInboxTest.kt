package dev.sam3vision.app.isee

import dev.sam3vision.app.isee.vm.FakeMessageDao
import dev.sam3vision.app.isee.vm.IseePhotoShot
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.glasses.CaptureAction
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La boîte aux lettres lunettes : photo affichée rue et épinglée en contexte,
 * caméra coupée puis relancée au tap, question entendue sans photo.
 */
class IseeGlassesInboxTest {

    private val dir: File = Files.createTempDirectory("isee-glasses-inbox").toFile().apply { deleteOnExit() }
    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(dir, dao)
    private val shots = IseePhotoShot(media = media, camera = { null }, encode = { "b64:${it.name}" })
    private var videoStops = 0
    private var probeStops = 0
    private var restarts = 0
    private var silences = 0
    private val answers = mutableListOf<Pair<String, ByteArray>>()

    private fun TestScopeInbox(): IseeGlassesInbox = IseeGlassesInbox(
        scope = testScope,
        shots = shots,
        snapshot = { state.value },
        update = { fn -> state.update(fn) },
        silence = { silences++ },
        answer = { text, pcm -> answers.add(text to pcm) },
        stopVideo = { videoStops++ },
        stopProbe = { probeStops++ },
        restartCamera = { restarts++ },
    )

    private lateinit var testScope: kotlinx.coroutines.CoroutineScope

    @Test
    fun `photo valide — affichee, epinglee, camera coupee`() = runTest {
        testScope = this
        state.value = IseeUiState(recordingVideo = true)
        val inbox = TestScopeInbox()

        inbox.onPhoto(photo("lunettes-1.jpg"), CaptureAction.PHOTO)

        assertNotNull("affichée", state.value.glassesPhoto)
        assertEquals("b64:lunettes-1.jpg", shots.imageBase64())
        assertEquals(1, videoStops)
        assertEquals(1, probeStops)
    }

    @Test
    fun `photo suivante — remplace la precedente et son contexte`() = runTest {
        testScope = this
        val inbox = TestScopeInbox()
        inbox.onPhoto(photo("lunettes-1.jpg"), CaptureAction.PHOTO)

        inbox.onPhoto(photo("lunettes-2.jpg"), CaptureAction.PHOTO)

        assertEquals("lunettes-2.jpg", state.value.glassesPhoto?.file?.name)
        assertEquals("b64:lunettes-2.jpg", shots.imageBase64())
    }

    @Test
    fun `doublon — ignore, camera intacte`() = runTest {
        testScope = this
        val inbox = TestScopeInbox()
        val file = photo("lunettes-1.jpg")
        inbox.onPhoto(file, CaptureAction.PHOTO)
        val stops = probeStops

        inbox.onPhoto(file, CaptureAction.PHOTO)

        assertEquals("toujours la première", "lunettes-1.jpg", state.value.glassesPhoto?.file?.name)
        assertEquals(stops, probeStops)
        assertEquals(0, restarts)
    }

    @Test
    fun `tap avec photo — photo partie, contexte efface, camera relancee`() = runTest {
        testScope = this
        val inbox = TestScopeInbox()
        inbox.onPhoto(photo("lunettes-1.jpg"), CaptureAction.PHOTO)

        assertTrue(inbox.onTap())

        assertNull(state.value.glassesPhoto)
        assertNull("plus de contexte", shots.imageBase64())
        assertEquals(1, restarts)
        assertFalse("deuxième tap : rien", inbox.onTap())
    }

    @Test
    fun `question entendue — sans photo, repondue comme une dictee`() = runTest {
        testScope = this
        val inbox = TestScopeInbox()

        inbox.heard("où sont les toilettes ?", byteArrayOf(7))
        testScheduler.advanceUntilIdle()

        assertNull("aucune photo affichée", state.value.glassesPhoto)
        assertEquals(1, silences)
        assertEquals(listOf("où sont les toilettes ?"), answers.map { it.first })
        assertEquals(listOf(byteArrayOf(7).asList()), answers.map { it.second.asList() })
    }

    private fun photo(name: String): File =
        File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
}
