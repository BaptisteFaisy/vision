package dev.sam3vision.app.isee

/**
 * Vœu retenu en place unique : la parole en cours va au bout, lui parle à
 * la fin du mot ([VoiceBus.onSpeechIdle]) — jamais par-dessus. Texte, clip,
 * chaîne, instant de rétention et clé d'antirépétition (rendue s'il ne se
 * dit jamais : périmé, remplacé, abandonné sur coupe).
 */
internal data class PendingWish(
    val level: Int,
    val text: String,
    val clipId: String?,
    val clips: List<String>,
    val atMs: Long,
    val key: String?,
)

/** Issue d'une prise ([VoicePending.take]) : vide, fraîche, ou périmée (clé à rendre). */
internal sealed interface PendingTake {
    data object Empty : PendingTake
    data class Fresh(val wish: PendingWish) : PendingTake
    data class Stale(val key: String?) : PendingTake
}

/**
 * Issue d'une rétention ([VoicePending.retain]) : rangée, rangée en
 * remplaçant (clé remplacée à rendre : elle ne se dira jamais), ou refusée
 * (le bus rend la clé du refusé).
 */
internal sealed interface RetainOutcome {
    data object Stored : RetainOutcome
    data class Replaced(val refundKey: String?) : RetainOutcome
    data object Rejected : RetainOutcome
}

/**
 * Place unique d'attente du bus vocal (ordre user du 03/11/2026 : rien ne
 * coupe une parole en cours — escaliers d'abord, § 44, puis tout le bus :
 * à la fin du mot se dit le plus prioritaire en attente, à égalité le
 * plus frais, c'est-à-dire la position flèche du moment).
 *
 * Rétention ([retain]) : le trottoir remplace tout ([OutdoorLevel.SIDEWALK],
 * absolu § 45), sinon l'incoupable en place barre ([uncuttableBlocker] :
 * escaliers barrent tout sauf escaliers et trottoir), sinon le plus petit
 * niveau gagne, à égalité le dernier (le frais remplace le rassis). Un seul
 * niveau d'attente suffit : les producteurs re-postent à chaque frame.
 *
 * Prise ([take]) : périmée ([STAIRS_PENDING_TTL_MS] pour un compte, le
 * compte a changé ; [PENDING_TTL_MS] sinon, la scène a changé — derrière
 * une longue réponse du chat, on ne dit pas une annonce d'il y a une
 * minute), elle se rend au lieu de se dire.
 *
 * Pur hors du rangement (aucun Android, horloge fournie) : testé en JVM.
 * Le bus l'appelle sous son verrou ; les remboursements restent au bus.
 */
internal class VoicePending {
    private var wish: PendingWish? = null

    /** Vrai si un vœu attend la fin du mot en cours. */
    val held: Boolean get() = wish != null

    /**
     * Retient [new] : rangé, remplacé (dernier gagnant à égalité, absolus
     * d'abord), ou refusé (barré par l'incoupable en place, ou sous son
     * rang — jamais deux d'attente).
     */
    fun retain(new: PendingWish): RetainOutcome {
        val current = wish
        if (current == null) {
            wish = new
            return RetainOutcome.Stored
        }
        if (uncuttableBlocker(current.level, new.level) != null) return RetainOutcome.Rejected
        if (new.level == OutdoorLevel.SIDEWALK || new.level <= current.level) {
            wish = new
            return RetainOutcome.Replaced(current.key)
        }
        return RetainOutcome.Rejected
    }

    /**
     * Prend le vœu en attente à [nowMs] : frais il se dit, périmé sa clé
     * se rend ([PendingTake.Stale]), vide rien à dire. Un seul par repos :
     * la place se remplit à la parole suivante.
     */
    fun take(nowMs: Long): PendingTake {
        val next = wish ?: return PendingTake.Empty
        wish = null
        val ttl = if (next.level == OutdoorLevel.STAIRS) STAIRS_PENDING_TTL_MS else PENDING_TTL_MS
        if (nowMs - next.atMs > ttl) return PendingTake.Stale(next.key)
        return PendingTake.Fresh(next)
    }

    /**
     * Abandonne le vœu en attente (coupe, micro user) : rend sa clé à
     * rembourser (`null` = rien n'attendait, rien à rendre).
     */
    fun abandon(): String? {
        val key = wish?.key
        wish = null
        return key
    }
}
