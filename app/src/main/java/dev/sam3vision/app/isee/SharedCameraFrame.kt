package dev.sam3vision.app.isee

import dev.sam3vision.core.share.SharedPhoto

/**
 * Dernière frame JPEG (base64, 320 px) de la caméra iSee, pour la page du proche.
 *
 * La caméra ne produit des frames que pendant qu'elle sert ([IseeCameraWiring]) :
 * cette copie de référence ne coûte donc rien et évite de faire transiter l'image
 * dans l'état Compose (une recomposition par frame à 20-30 ips).
 *
 * Une frame n'est proposée que [FRESH_MS] : quand l'enregistrement s'arrête, la
 * page montre l'image figée un court instant puis l'efface, au lieu d'une photo
 * périmée présentée comme du direct.
 */
internal object SharedCameraFrame {

    /** Durée pendant laquelle une frame est encore considérée comme du direct. */
    private const val FRESH_MS = 6_000L

    @Volatile
    private var jpeg: String? = null

    @Volatile
    private var capturedAtMs: Long = 0L

    /** Mémorise la frame qui vient d'être convertie par l'analyseur caméra. */
    fun offer(frame: String) {
        jpeg = frame
        capturedAtMs = System.currentTimeMillis()
    }

    /**
     * Photo à publier au proche : la dernière frame si elle a moins de [FRESH_MS],
     * horodatée à l'instant de l'envoi ([nowMs]) — c'est la fraîcheur de l'envoi
     * qui compte côté page, pas celle du capteur.
     */
    fun photoOf(nowMs: Long): SharedPhoto? =
        jpeg?.takeIf { nowMs - capturedAtMs <= FRESH_MS }?.let { SharedPhoto(it, nowMs) }

    /** Caméra fermée : plus rien à montrer au proche. */
    fun clear() {
        jpeg = null
        capturedAtMs = 0L
    }
}
