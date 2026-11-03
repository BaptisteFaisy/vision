package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vœu de test : niveau, texte, instant et clé (clips vides). */
private fun pendingWish(level: Int, text: String, atMs: Long = 0L, key: String? = null) =
    PendingWish(level, text, null, emptyList(), atMs, key)

/**
 * Place unique d'attente ([VoicePending], ordre user du 03/11/2026 : rien
 * ne coupe) : premier rangé, plus petit niveau ou absolu remplace, à
 * égalité le dernier (le frais), le reste est refusé ; périmée, la place
 * se rend au lieu de se dire. Pure JVM, sans bus ni Android.
 */
class VoicePendingTest {

    @Test
    fun `premier range, pris frais`() {
        val pending = VoicePending()

        assertFalse(pending.held)
        assertEquals(RetainOutcome.Stored, pending.retain(pendingWish(OutdoorLevel.HUMAN, "Gauche.", atMs = 1_000L)))
        assertTrue(pending.held)

        assertEquals(
            PendingTake.Fresh(pendingWish(OutdoorLevel.HUMAN, "Gauche.", atMs = 1_000L)),
            pending.take(1_500L),
        )
        assertFalse(pending.held)
        assertEquals(PendingTake.Empty, pending.take(1_500L))
    }

    @Test
    fun `a egalite le dernier remplace, cle rendue`() {
        val pending = VoicePending()

        pending.retain(pendingWish(OutdoorLevel.FALLBACK, "Gauche.", key = "fleche"))
        assertEquals(
            RetainOutcome.Replaced("fleche"),
            pending.retain(pendingWish(OutdoorLevel.FALLBACK, "Droite.", key = "fleche2")),
        )

        val taken = pending.take(100L) as PendingTake.Fresh
        assertEquals("Droite.", taken.wish.text)
    }

    @Test
    fun `plus petit niveau remplace, plus grand refuse`() {
        val pending = VoicePending()

        pending.retain(pendingWish(OutdoorLevel.FENCE, "Clôture."))
        assertEquals(RetainOutcome.Replaced(null), pending.retain(pendingWish(OutdoorLevel.HUMAN, "Gauche.")))
        assertEquals(RetainOutcome.Rejected, pending.retain(pendingWish(OutdoorLevel.POLE, "Poteau.")))

        val taken = pending.take(100L) as PendingTake.Fresh
        assertEquals(OutdoorLevel.HUMAN, taken.wish.level)
    }

    @Test
    fun `trottoir remplace les escaliers, pas l inverse`() {
        val pending = VoicePending()

        pending.retain(pendingWish(OutdoorLevel.STAIRS, "5 marches."))
        assertEquals(
            RetainOutcome.Replaced(null),
            pending.retain(pendingWish(OutdoorLevel.SIDEWALK, "Trottoir.")),
        )
        assertEquals(RetainOutcome.Rejected, pending.retain(pendingWish(OutdoorLevel.STAIRS, "4 marches.")))

        val taken = pending.take(100L) as PendingTake.Fresh
        assertEquals(OutdoorLevel.SIDEWALK, taken.wish.level)
    }

    @Test
    fun `escaliers en place barrent les autres sauf trottoir`() {
        val pending = VoicePending()

        pending.retain(pendingWish(OutdoorLevel.STAIRS, "5 marches."))
        assertEquals(RetainOutcome.Rejected, pending.retain(pendingWish(OutdoorLevel.HUMAN, "Gauche.")))
        assertEquals(
            RetainOutcome.Replaced(null),
            pending.retain(pendingWish(OutdoorLevel.STAIRS, "4 marches.")),
        )
    }

    @Test
    fun `compte perime apres un cycle, autres apres 4 s`() {
        val pending = VoicePending()

        pending.retain(pendingWish(OutdoorLevel.STAIRS, "5 marches.", atMs = 1_000L, key = "compte"))
        assertEquals(PendingTake.Stale("compte"), pending.take(2_001L))

        pending.retain(pendingWish(OutdoorLevel.HUMAN, "Gauche.", atMs = 1_000L, key = "humain"))
        assertEquals(
            PendingTake.Fresh(pendingWish(OutdoorLevel.HUMAN, "Gauche.", atMs = 1_000L, key = "humain")),
            pending.take(5_000L),
        )
        pending.retain(pendingWish(OutdoorLevel.HUMAN, "Gauche.", atMs = 1_000L, key = "humain"))
        assertEquals(PendingTake.Stale("humain"), pending.take(5_001L))
    }

    @Test
    fun `abandon rend la cle et vide`() {
        val pending = VoicePending()

        assertEquals(null, pending.abandon())
        pending.retain(pendingWish(OutdoorLevel.HUMAN, "Gauche.", key = "humain"))
        assertEquals("humain", pending.abandon())
        assertFalse(pending.held)
    }
}
