package dev.sam3vision.app.glasses

import com.meta.wearable.dat.motion.Motion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import com.meta.wearable.dat.motion.types.MotionError
import com.meta.wearable.dat.motion.types.MotionSample
import com.meta.wearable.dat.motion.types.MotionSource
import com.meta.wearable.dat.motion.types.MotionState
import com.meta.wearable.dat.motion.types.Quaternion
import dev.sam3vision.core.distance.CameraPose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pose tête : échantillon → pose publiée, absence/erreur/stop → `null`. */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesHeadPoseTest {

    @After
    fun `la pose publiee ne fuit pas vers les autres tests`() {
        GlassesVideoLink.clear()
    }

    @Test
    fun `orientation publie la pose yeux`() = runTest {
        val motion = FakeMotion()
        val feed = GlassesHeadPose(this, onNote = {})
        feed.start(attach = { motion }, detach = {})
        runCurrent()

        // Hochement bas 30° : q = (−sin15°, 0, 0, cos15°).
        motion.emit(MotionSample(1L, null, null, null, Quaternion(-0.258819f, 0f, 0f, 0.9659258f), MotionSource.GLASSES))
        runCurrent()

        val pose = GlassesVideoLink.headPose.value
        assertTrue(pose != null && pose.heightMeters == CameraPose.GLASSES_HEIGHT_M)
        assertEquals(0.5236f, pose!!.pitchRad, 1e-3f)
        feed.stop()
    }

    @Test
    fun `orientation nulle publie null`() = runTest {
        val motion = FakeMotion()
        val feed = GlassesHeadPose(this, onNote = {})
        feed.start(attach = { motion }, detach = {})
        runCurrent()

        motion.emit(MotionSample(1L, null, null, null, null, MotionSource.GLASSES))
        runCurrent()

        assertNull(GlassesVideoLink.headPose.value)
        feed.stop()
    }

    @Test
    fun `refus attache publie null et previent`() = runTest {
        var notes = 0
        val feed = GlassesHeadPose(this, onNote = { notes += 1 })

        feed.start(attach = { null }, detach = {})
        runCurrent()

        assertNull(GlassesVideoLink.headPose.value)
        assertEquals(1, notes)
        feed.stop()
    }

    @Test
    fun `stop arrete detache et publie null`() = runTest {
        val motion = FakeMotion()
        var detached = 0
        val feed = GlassesHeadPose(this, onNote = {})
        feed.start(attach = { motion }, detach = { detached += 1 })
        runCurrent()

        feed.stop()

        assertTrue(motion.stopped)
        assertEquals(1, detached)
        assertNull(GlassesVideoLink.headPose.value)
    }

    @Test
    fun `erreur motion oublie la pose`() = runTest {
        val motion = FakeMotion()
        val feed = GlassesHeadPose(this, onNote = {})
        feed.start(attach = { motion }, detach = {})
        runCurrent()

        motion.emit(MotionSample(1L, null, null, null, Quaternion(-0.258819f, 0f, 0f, 0.9659258f), MotionSource.GLASSES))
        runCurrent()
        assertTrue(GlassesVideoLink.headPose.value != null)

        motion.emitError(MotionError.SENSOR_UNAVAILABLE)
        runCurrent()

        assertNull(GlassesVideoLink.headPose.value)
        feed.stop()
    }

    @Test
    fun `10 orientations nulles previennent une seule fois`() = runTest {
        val motion = FakeMotion()
        val notes = ArrayList<GlassesMessage>()
        val feed = GlassesHeadPose(this, onNote = { notes += it })
        feed.start(attach = { motion }, detach = {})
        runCurrent()

        repeat(12) {
            motion.emit(MotionSample(1L, null, null, null, null, MotionSource.GLASSES))
            runCurrent()
        }

        assertNull(GlassesVideoLink.headPose.value)
        assertEquals(1, notes.size)
        assertEquals(GlassesMessages.headPoseNoFusion(), notes.single())
        feed.stop()
    }

    @Test
    fun `une orientation recue reamorce l'alerte sans fusion`() = runTest {
        val motion = FakeMotion()
        var notes = 0
        val feed = GlassesHeadPose(this, onNote = { notes += 1 })
        feed.start(attach = { motion }, detach = {})
        runCurrent()

        repeat(10) {
            motion.emit(MotionSample(1L, null, null, null, null, MotionSource.GLASSES))
            runCurrent()
        }
        assertEquals(1, notes)

        motion.emit(MotionSample(1L, null, null, null, Quaternion(-0.258819f, 0f, 0f, 0.9659258f), MotionSource.GLASSES))
        runCurrent()
        assertTrue(GlassesVideoLink.headPose.value != null)

        repeat(10) {
            motion.emit(MotionSample(1L, null, null, null, null, MotionSource.GLASSES))
            runCurrent()
        }
        assertEquals(2, notes)
        feed.stop()
    }

    @Test
    fun `la hauteur des yeux reglee est portee par la pose`() = runTest {
        val motion = FakeMotion()
        val feed = GlassesHeadPose(this, onNote = {}, eyeHeightM = { 1.8f })
        feed.start(attach = { motion }, detach = {})
        runCurrent()

        motion.emit(MotionSample(1L, null, null, null, Quaternion(-0.258819f, 0f, 0f, 0.9659258f), MotionSource.GLASSES))
        runCurrent()

        val pose = GlassesVideoLink.headPose.value
        assertTrue(pose != null && pose.heightMeters == 1.8f)
        feed.stop()
    }

    @Test
    fun `le log de validation n'empeche pas la pose`() = runTest {
        val motion = FakeMotion()
        val feed = GlassesHeadPose(this, onNote = {})
        feed.start(attach = { motion }, detach = {})
        runCurrent()

        // Plusieurs échantillons : le log 1 Hz passe au premier sans bloquer.
        repeat(3) {
            motion.emit(MotionSample(1L, null, null, null, Quaternion(-0.258819f, 0f, 0f, 0.9659258f), MotionSource.GLASSES))
            runCurrent()
        }

        assertTrue(GlassesVideoLink.headPose.value != null)
        feed.stop()
    }

    /** Faux Motion : `samples` piloté à la main, `errors` au repos puis pilotées. */
    private class FakeMotion : Motion {
        private val _samples = MutableSharedFlow<MotionSample>()
        private val _errors = MutableStateFlow(MotionError.DEVICE_DISCONNECTED)
        var stopped = false

        suspend fun emit(sample: MotionSample) = _samples.emit(sample)
        fun emitError(error: MotionError) {
            _errors.value = error
        }

        override fun start() = Unit
        override fun stop() {
            stopped = true
        }
        override val state = MutableStateFlow(MotionState.STARTED)
        override val samples = _samples
        override val errors: StateFlow<MotionError> = _errors
        override val configuration = MotionConfiguration()
    }
}
