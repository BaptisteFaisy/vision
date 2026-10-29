package dev.sam3vision.app.isee

import dev.sam3vision.core.danger.DangerSnapshot
import dev.sam3vision.core.danger.DangerTrack
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.LensSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fusion danger → voix (JUnit seul) : les pistes YOLO sont mesurées au
 * sténopé et rendues comme labels placés ; sans piste, sans dimensions ou
 * distances coupées, rien ne fusionne (la voix parle comme avant).
 */
class DangerPlacedTest {

    private fun track(): DangerTrack {
        // Boîte 250 px de haut sur 512 (côté droit) ≈ 2,6 m au sténopé.
        val h = 250f / 512f
        return DangerTrack(7, "personne", 0.8f, listOf(0.6f, 0.5f - h / 2f, 0.8f, 0.5f + h / 2f), 3, 0, 0L, 0L)
    }

    @Test
    fun `une piste mesuree donne un label place avec sa distance`() {
        val labels = dangerPlaced(DistanceMeter(LensSpec.Default), DangerSnapshot(listOf(track()), 512, 512))
        assertEquals(1, labels.size)
        assertEquals("personne", labels[0].label)
        assertEquals(0.7f, labels[0].centerX, 1e-6f)
        assertEquals(2.63f, labels[0].meters!!, 0.05f)
    }

    @Test
    fun `sans piste ni dimensions rien ne fusionne`() {
        val meter = DistanceMeter(LensSpec.Default)
        assertTrue(dangerPlaced(meter, DangerSnapshot.Empty).isEmpty())
        assertTrue(dangerPlaced(meter, DangerSnapshot(listOf(track()), 0, 0)).isEmpty())
    }

    @Test
    fun `distances coupees rien ne fusionne`() {
        val meter = DistanceMeter(LensSpec.Default)
        meter.enabled = false
        assertTrue(dangerPlaced(meter, DangerSnapshot(listOf(track()), 512, 512)).isEmpty())
    }
}
