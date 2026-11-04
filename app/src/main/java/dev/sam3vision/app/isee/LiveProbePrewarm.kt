package dev.sam3vision.app.isee

import dev.sam3vision.core.net.warmLiveNetwork

/**
 * Pré-réveil iSee : la scène caméra est affichée, la permission est accordée et
 * la liaison commence — le boot GPU part SANS attendre la fin de la liaison
 * (~1-3 s gagnées sur chaque connexion à froid : CameraX, `attachCamera`,
 * premier `configure`).
 *
 * Garde stricte, la même que `configure` : rien sans endpoint, rien si le
 * budget froid n'est pas dû ([ProbeLink.coldDue] — carte chaude ou réveil
 * récent). [warm] réchauffe le chemin réseau local (clients partagés + DNS,
 * aucun appel GPU) avant le `/health` patient de [LiveProbe.fireWake],
 * réutilisé tel quel — un seul réveil en vol par endpoint (`GpuWake`).
 */
internal fun LiveProbe.prewarm(url: String, warm: (String) -> Unit = { warmLiveNetwork(it) }) {
    val clean = url.trim()
    if (clean.isEmpty() || !link.coldDue(clean)) return
    warm(clean)
    fireWake(clean)
}
