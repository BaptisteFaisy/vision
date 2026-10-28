package dev.sam3vision.core.ground

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneLabelsTest {

    @Test
    fun `classify suit la distance signee avec ordre kinbn`() {
        val distances = floatArrayOf(0f, 0.05f, 0.12f, 0.5f, 2f, 2.5f, -0.10f, -0.18f, -0.25f, 0f)
        val n = distances.size
        val cloud = PointCloud(
            n, 1,
            FloatArray(n),
            FloatArray(n) { i -> distances[i] - SyntheticScene.CAM_HEIGHT },
            FloatArray(n) { 1f },
            BooleanArray(n) { i -> i != n - 1 },
        )

        val labels = SceneLabels.classify(cloud, SyntheticScene.floorPlane())

        val expected = intArrayOf(
            SceneLabels.GROUND,
            SceneLabels.GROUND,
            SceneLabels.OBSTACLE,
            SceneLabels.OBSTACLE,
            SceneLabels.OBSTACLE,
            SceneLabels.CEILING,
            SceneLabels.GROUND,
            SceneLabels.INVALID,
            SceneLabels.DROPOFF,
            SceneLabels.INVALID,
        )
        for (i in expected.indices) {
            assertEquals("point $i (d=${distances[i]})", expected[i], labels[i])
        }
    }

    @Test
    fun `classify sans plan rend tout invalide`() {
        val cloud = SyntheticScene.cloudOf(FloatArray(8) { 2f }, 8, 1)

        val labels = SceneLabels.classify(cloud, null)

        for (label in labels) {
            assertEquals(SceneLabels.INVALID, label)
        }
    }
}
