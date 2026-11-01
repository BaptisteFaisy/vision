package dev.sam3vision.app.isee

import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.live.GlassesMireStore
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.CropGeometry
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bascule du calibrage distances téléphone ↔ lunettes : lens, pose, pistes, sessions. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveProbeCalibrationTest {

    @After
    fun `le direct lunettes ne fuit pas vers les autres tests`() {
        // `live` est un singleton : sans nettoyage, les tests suivants verraient
        // des frames lunettes (configureFrom lit live.value par défaut).
        GlassesVideoLink.clear()
    }

    @Test
    fun `setCalibration pose le lens et la pose sans fermer les sessions`() = runTest {
        val (probe, fake) = probeHarness(this)

        probe.setCalibration(LensSpec.Glasses, CameraPose.Glasses)

        assertEquals(LensSpec.Glasses, probe.distances.lensSpec)
        assertEquals(CameraPose.Glasses, probe.distances.pose)
        assertNotNull(probe.session)
        assertNotNull(probe.coldSession)
        assertEquals(0, fake.closed)
    }

    @Test
    fun `setCalibration oublie les pistes de l'ancien capteur`() = runTest {
        val (probe, _) = probeHarness(this)
        val detection = Detection("personne", 1f, listOf(0f, 10f, 100f, 210f), 512, 384)
        val measured = probe.distances.measure(512, 384, listOf(detection), listOf(7))
        assertEquals(1, measured.size)
        assertTrue(probe.distances.predictions().isNotEmpty())

        probe.setCalibration(LensSpec.Glasses, CameraPose.Glasses)

        assertTrue(probe.distances.predictions().isEmpty())
    }

    @Test
    fun `setCalibration avec un lens inutilisable garde l'ancien`() = runTest {
        val (probe, _) = probeHarness(this)
        probe.setCalibration(LensSpec.Glasses, CameraPose.Glasses)

        probe.setCalibration(LensSpec(0f, 0f))

        assertEquals(LensSpec.Glasses, probe.distances.lensSpec)
    }

    @Test
    fun `observeGlassesLive bascule au direct lunettes puis revient`() = runTest {
        val (probe, _) = probeHarness(this)
        var glasses = 0
        var phone = 0
        val observer = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            probe.observeGlassesLive(observer, { Settings.Default }, { null }, { glasses += 1 }, { phone += 1 })
            runCurrent()
            // Collecte initiale : pas de lunettes, voie téléphone.
            assertEquals(LensSpec.Default, probe.distances.lensSpec)
            assertNull(probe.distances.pose)
            assertEquals(1, phone)

            GlassesVideoLink.markRendered()
            runCurrent()

            assertEquals(LensSpec.Glasses, probe.distances.lensSpec)
            assertEquals(CameraPose.Glasses, probe.distances.pose)
            assertEquals(1, glasses)

            GlassesVideoLink.clear()
            runCurrent()

            assertEquals(LensSpec.Default, probe.distances.lensSpec)
            assertNull(probe.distances.pose)
            assertEquals(2, phone)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun `configureFrom route les lunettes vers le repli estime`() = runTest {
        val (probe, _) = probeHarness(this)

        probe.configureFrom(Settings.Default, fromGlasses = true)
        assertEquals(LensSpec.Glasses, probe.distances.lensSpec)

        probe.configureFrom(Settings.Default, fromGlasses = false)
        assertEquals(LensSpec.Default, probe.distances.lensSpec)
    }

    @Test
    fun `configureFrom prefere la mire au repli estime`() = runTest {
        val (probe, _) = probeHarness(this)
        val mire = LensSpec(60.61f, 92.19f, sensorLandscape = false)

        probe.configureFrom(Settings.Default, glassesLens = mire, fromGlasses = true)

        assertEquals(mire, probe.distances.lensSpec)
        assertEquals(mire, GlassesVideoLink.glassesStreamLens)
    }

    @Test
    fun `save mire reapplique le stack sans bascule live`() = runTest {
        val (probe, _) = probeHarness(this)
        var reads = 0
        var mire: LensSpec? = null
        val observer = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            probe.observeGlassesLive(
                observer, { Settings.Default }, { null }, {}, {},
                glassesLens = { reads += 1; mire },
            )
            runCurrent()
            GlassesVideoLink.markRendered()
            runCurrent()
            val readsAtLive = reads
            assertEquals(LensSpec.Glasses, probe.distances.lensSpec)

            mire = LensSpec(60.61f, 92.19f, sensorLandscape = false)
            GlassesMireStore.mireVersion.value += 1
            runCurrent()

            assertTrue(reads > readsAtLive)
            assertEquals(mire, probe.distances.lensSpec)
            assertEquals(mire, GlassesVideoLink.glassesStreamLens)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun `setGlassesLive avec pose tete rouvre la branche sol sur la tete`() = runTest {
        val (probe, _) = probeHarness(this)
        val head = CameraPose(CameraPose.GLASSES_HEIGHT_M, 0.3f)

        probe.setGlassesLive(true, head)

        assertEquals(head, probe.distances.pose)
        assertTrue(probe.distances.groundPlaneEnabled)
    }

    @Test
    fun `setGlassesLive sans pose tete coupe le circuit`() = runTest {
        val (probe, _) = probeHarness(this)
        probe.distances.pose = CameraPose(1.5f, 0.1f)

        probe.setGlassesLive(true)

        assertNull(probe.distances.pose)
        assertTrue(!probe.distances.groundPlaneEnabled)
    }

    @Test
    fun `setGlassesLive refuse une pose tete inexploitable`() = runTest {
        val (probe, _) = probeHarness(this)

        probe.setGlassesLive(true, CameraPose(99f, 0f))

        assertNull(probe.distances.pose)
        assertTrue(!probe.distances.groundPlaneEnabled)
    }

    @Test
    fun `observeHeadPose applique la pose fraiche quand le direct vit`() = runTest {
        val (probe, _) = probeHarness(this)
        val observer = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            probe.observeHeadPose(observer)
            probe.setGlassesLive(true)
            GlassesVideoLink.markRendered()
            val head = CameraPose(CameraPose.GLASSES_HEIGHT_M, 0.2f)
            GlassesVideoLink.publishHeadPose(head)
            runCurrent()

            assertEquals(head, probe.distances.pose)
            assertTrue(probe.distances.groundPlaneEnabled)

            GlassesVideoLink.publishHeadPose(null)
            runCurrent()

            assertNull(probe.distances.pose)
            assertTrue(!probe.distances.groundPlaneEnabled)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun `observeHeadPose ignore une pose perimee`() = runTest {
        val (probe, _) = probeHarness(this)
        val observer = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            probe.observeHeadPose(observer)
            probe.setGlassesLive(true)
            GlassesVideoLink.markRendered()
            val head = CameraPose(CameraPose.GLASSES_HEIGHT_M, 0.2f)
            GlassesVideoLink.publishHeadPose(head, nowMs = 1L)
            runCurrent()

            assertNull(probe.distances.pose)
            assertTrue(!probe.distances.groundPlaneEnabled)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun `observeHeadPose ignore la pose hors direct`() = runTest {
        val (probe, _) = probeHarness(this)
        val observer = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            probe.observeHeadPose(observer)
            GlassesVideoLink.publishHeadPose(CameraPose(CameraPose.GLASSES_HEIGHT_M, 0.2f))
            runCurrent()

            assertNull(probe.distances.pose)
            assertTrue(probe.distances.groundPlaneEnabled)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun `E5 pose la focale recadree par photo vue`() = runTest {
        val (probe, _) = probeHarness(this)
        val stream = LensSpec(68f, 100.4f, sensorLandscape = false)
        GlassesVideoLink.glassesStreamLens = stream
        GlassesVideoLink.noteSnapshotGeometry(720, 1280, 320, 180)

        applyGlassesLens(probe, fromView = true)

        val expected = CropGeometry.afterCrop(stream, 720, 1280, CropGeometry.fillViewCrop(720, 1280, 320, 180))!!
        assertEquals(expected, probe.distances.lensSpec)
        GlassesVideoLink.detachSnapshot()
    }

    @Test
    fun `E5 hors vue repose le flux, sans focale ne touche rien`() = runTest {
        val (probe, _) = probeHarness(this)
        val stream = LensSpec(68f, 100.4f, sensorLandscape = false)
        GlassesVideoLink.glassesStreamLens = stream

        applyGlassesLens(probe, fromView = false)
        assertEquals(stream, probe.distances.lensSpec)

        GlassesVideoLink.glassesStreamLens = null
        applyGlassesLens(probe, fromView = true)
        assertEquals(stream, probe.distances.lensSpec)
    }

    @Test
    fun `groundDegraded reflete la bascule sol`() = runTest {
        val (probe, _) = probeHarness(this)
        assertTrue(!probe.groundDegraded)
        probe.setGlassesLive(true)
        assertTrue(probe.groundDegraded)
        probe.setGlassesLive(false)
        assertTrue(!probe.groundDegraded)
    }
}
