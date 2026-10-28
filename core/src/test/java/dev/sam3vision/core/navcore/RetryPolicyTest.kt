package dev.sam3vision.core.navcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille la politique de réessai ([RetryPolicy]) : backoff exponentiel
 * déterministe, plafond, et porte `réessayable + tentatives restantes`.
 */
class RetryPolicyTest {

    private val policy = RetryPolicy()

    @Test
    fun `la premiere tentative ne dort pas`() {
        assertEquals(0L, policy.delayFor(1))
        assertEquals(0L, policy.delayFor(0))
        assertEquals(0L, policy.delayFor(-3))
    }

    @Test
    fun `le delai double a chaque tentative`() {
        assertEquals(500L, policy.delayFor(2))
        assertEquals(1_000L, policy.delayFor(3))
        assertEquals(2_000L, policy.delayFor(4))
    }

    @Test
    fun `le delai est plafonne`() {
        assertEquals(8_000L, policy.delayFor(10))
        assertEquals(8_000L, policy.delayFor(100))
    }

    @Test
    fun `on reessaie une erreur reessayable tant qu il reste des tentatives`() {
        assertTrue(policy.shouldRetry(NavError.RouteFailed("x"), 1))
        assertTrue(policy.shouldRetry(NavError.RouteFailed("x"), 2))
        assertFalse(policy.shouldRetry(NavError.RouteFailed("x"), 3))
    }

    @Test
    fun `on ne reessaie jamais une erreur non reessayable`() {
        assertFalse(policy.shouldRetry(NavError.MicBusy(), 1))
        assertFalse(policy.shouldRetry(NavError.AsrFailed("x"), 1))
        assertFalse(policy.shouldRetry(NavError.LaunchFailed("x"), 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxAttempts zero est refuse`() {
        RetryPolicy(maxAttempts = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plafond sous la base est refuse`() {
        RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 100L)
    }
}
