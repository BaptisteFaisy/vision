package dev.sam3vision.core.exposure

/**
 * Verrou AWB **temporisé** : on verrouille le bilan des blancs tant qu'une boîte de feu
 * est vue, puis on le relâche dès qu'elle disparaît. Raison : sans verrou, l'AWB se
 * re-règle sur la scène entière (un bus qui passe, une façade claire) et la teinte du feu
 * dérive d'une frame à l'autre — d'où les bascules rouge ↔ vert qu'on veut couper.
 *
 * Pourquoi temporisé, et pourquoi ce n'est pas un verrou AE : le dépôt a mesuré le
 * 2026-11-01 qu'un verrou posé au binding figeait l'exposition **avant** convergence
 * (aperçu surexposé) ; ici le verrou n'est jamais posé sans objet à regarder, et il tombe
 * seul après [HOLD_MS] — une teinte figée oubliée rendrait la caméra aveugle au chaud/froid
 * de l'éclairage urbain. Anti-rebond [SETTLE_MS] : deux bascules ne se suivent pas.
 *
 * Pur JVM (aucune API caméra) : `tick` dit *quoi* faire, l'appelant écrit
 * `CONTROL_AWB_LOCK` dans **une** requête fusionnée avec l'antibanding et l'AF (l'API
 * CameraX écrase la précédente). Testé en JVM.
 */
class AwbLockPolicy(
    /** Silence toléré après la dernière boîte vue avant de relâcher (ms). */
    private val holdMs: Long = HOLD_MS,
    /** Délai minimal entre deux bascules (ms). */
    private val settleMs: Long = SETTLE_MS,
) {
    /** Dernière frame où une boîte de feu était vue (ms monotones). */
    private var lastSeenMs: Long = -HOLD_MS

    /** Dernière bascule appliquée (ms) : anti-rebond. */
    private var lastToggleMs: Long = -SETTLE_MS

    /**
     * Verrou voulu après cette frame : `true` = verrouiller, `false` = relâcher, `null` =
     * ne rien changer. [lightVisible] = une boîte de feu a été lue sur cette frame,
     * [locked] = état appliqué au téléphone, [nowMs] = horloge monotone (ms).
     */
    fun tick(lightVisible: Boolean, nowMs: Long, locked: Boolean): Boolean? {
        if (nowMs - lastToggleMs < settleMs) return null
        if (lightVisible) {
            lastSeenMs = nowMs
            if (locked) return null
            lastToggleMs = nowMs
            return true
        }
        if (!locked) return null
        if (nowMs - lastSeenMs < holdMs) return null
        lastToggleMs = nowMs
        return false
    }

    companion object {
        /** 3 s sans boîte : le feu est sorti du champ, la teinte doit redevenir libre. */
        const val HOLD_MS = 3000L

        /** Anti-rebond : une bascule par demi-seconde au plus. */
        const val SETTLE_MS = 500L
    }
}
