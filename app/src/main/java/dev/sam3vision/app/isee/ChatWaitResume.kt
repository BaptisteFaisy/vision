package dev.sam3vision.app.isee

/**
 * Attente et reprise de la réponse du chat (niveau 9, SPEC v4 § 9) : la
 * SEULE file restante côté chat. Quand un niveau plus prioritaire parle
 * (0 à 8 — danger, rappel GPS, parole user), la réponse attend au lieu
 * d'être perdue ; dès que son rang reparle, elle reprend depuis la
 * coupure (tranche interrompue rejouée), jamais depuis le début.
 *
 * Pas d'anti-répétition (spec : chat aucune), pas de péremption : une
 * réponse coupée reste due jusqu'à sa reprise ou son remplacement — une
 * nouvelle réponse écrase l'attente (overwrite, jamais deux files).
 * Traversée : le chat est muet, l'appelant ne reprend pas en crossing.
 *
 * Pure JVM (aucun contexte, aucune coroutine) : testée en JVM.
 */
internal class ChatWaitResume {

    /**
     * Réponse interrompue : [rowId] la bulle lue, [remaining] les tranches
     * à rejouer depuis la coupure (tranche interrompue incluse).
     */
    internal data class ChatPending(val rowId: Long, val remaining: List<String>)

    /** Réponse en attente de reprise, `null` si aucune. */
    @Volatile
    private var pending: ChatPending? = null

    /** Vrai si une réponse coupée attend sa reprise. */
    fun hasPending(): Boolean = pending != null

    /** Réponse en attente, `null` si aucune (sans la consommer). */
    fun peekDue(): ChatPending? = pending

    /**
     * Garde [remaining] (tranches depuis la coupure) pour [rowId] : une
     * seule attente, la plus fraîche — une nouvelle réponse écrase
     * l'ancienne, jamais deux files. Vide = rien à garder, attente jetée.
     */
    fun hold(rowId: Long, remaining: List<String>) {
        pending = remaining.takeIf { it.isNotEmpty() }?.let { ChatPending(rowId, it.toList()) }
    }

    /**
     * Prend la réponse due et la jette (une seule reprise) : `null` si
     * aucune. Une reprise re-coupée re-garde son reste via [hold].
     */
    fun takeDue(): ChatPending? {
        val due = pending
        pending = null
        return due
    }

    /** Jette l'attente (nouvelle réponse, nouvelle session). */
    fun clear() {
        pending = null
    }

    companion object {
        /** Rang du chat dans l'écrasement dehors (SPEC v4 § 9). */
        const val LEVEL = 9

        /**
         * Vrai si le niveau [level] fait attendre le chat : tout niveau
         * plus prioritaire (plus petit nombre — danger, rappel GPS,
         * parole user) ; le chat ne cède ni à son rang ni aux rangs
         * inférieurs (trottoir, GPS doux repris ailleurs).
         */
        fun yieldsTo(level: Int): Boolean = level < LEVEL
    }
}
