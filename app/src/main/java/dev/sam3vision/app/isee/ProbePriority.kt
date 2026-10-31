package dev.sam3vision.app.isee

/**
 * Priorité voix des guetteurs : P1 (véhicule en mouvement) et P2 (marche
 * imminente — alertes `SafetyVoice`) passent devant le poteau (P3), qui attend
 * en file au lieu de couper ou de chevaucher. Extrait de `ProbeWiring`
 * (plafond 500 lignes). Pur, testé en JVM.
 */

/** P1 en cours : un « véhicule en mouvement » couvre les 4 s qui suivent (son cooldown). */
internal const val P1_BUSY_MS = 4_000L

/** P2 en cours : une marche imminente couvre les 2 s qui suivent (prior : ≈ 2 s de parole, à valider rue). */
internal const val P2_BUSY_MS = 2_000L

/**
 * P1/P2 en cours à [nowMs] : véhicule en mouvement dit il y a moins de [P1_BUSY_MS],
 * ou marche imminente dite il y a moins de [P2_BUSY_MS] — le poteau (P3) attend alors
 * (doux + file, jamais de coupure). Fenêtres provisoires, non mesurées : à valider rue
 * (si P3 parle sur P1/P2, élargir ; s'il se tait trop, resserrer). Pur, testé en JVM.
 */
internal fun p1p2Busy(nowMs: Long, lastMovingAlertMs: Long?, lastNearMs: Long?): Boolean {
    if (lastMovingAlertMs != null && nowMs - lastMovingAlertMs < P1_BUSY_MS) return true
    if (lastNearMs != null && nowMs - lastNearMs < P2_BUSY_MS) return true
    return false
}

/**
 * Le poteau coupe : imminent ([cueUrgent]) et P1/P2 silencieux ([busy] faux — le
 * poteau est P3). Pur, testé en JVM.
 */
internal fun shouldCutForHazard(cueUrgent: Boolean, busy: Boolean): Boolean = cueUrgent && !busy
