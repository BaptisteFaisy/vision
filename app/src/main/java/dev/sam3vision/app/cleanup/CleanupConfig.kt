package dev.sam3vision.app.cleanup

/**
 * **Le** fichier de réglage du nettoyage. Un seul endroit pour changer la périodicité,
 * les types purgés et la rétention.
 *
 * - [PERIOD_HOURS] = 1 : purge horaire de tout ce que l'app a capturé (rétention ~1 h).
 *   Pour « supprimer ce qui a plus d'une heure », passer [MAX_AGE_MINUTES] à 60.
 * - [DELETE_IMAGES] / [DELETE_VIDEOS] : couper un type sans toucher à l'autre.
 * - [MAX_AGE_MINUTES] = 0 : tout supprimer à chaque passe (pas de rétention).
 * - [IN_PROGRESS_GRACE_SECONDS] : une capture modifiée il y a moins de 60 s est encore
 *   en cours d'écriture → reportée à la passe suivante.
 *
 * Aucune de ces valeurs ne dépend du réseau : le job tourne hors connexion.
 */
data class CleanupConfig(
    val periodHours: Int = PERIOD_HOURS,
    val deleteImages: Boolean = DELETE_IMAGES,
    val deleteVideos: Boolean = DELETE_VIDEOS,
    val maxAgeMinutes: Long = MAX_AGE_MINUTES,
    val inProgressGraceSeconds: Long = IN_PROGRESS_GRACE_SECONDS,
    val addToGallery: Boolean = ADD_TO_GALLERY,
) {
    companion object {
        const val PERIOD_HOURS = 1
        const val DELETE_IMAGES = true
        const val DELETE_VIDEOS = true
        const val MAX_AGE_MINUTES = 0L
        const val IN_PROGRESS_GRACE_SECONDS = 60L

        /** Par défaut les captures restent dans le stockage privé : invisible dans la galerie. */
        const val ADD_TO_GALLERY = false

        /** Nom unique du job : `KEEP` s'appuie dessus pour ne jamais doubler la purge. */
        const val WORK_NAME = "samvision-capture-cleanup"

        val Default = CleanupConfig()

        /** Intervalle en minutes, borné au minimum WorkManager (15 min). */
        fun intervalMinutes(hours: Int): Long = (hours.coerceAtLeast(1) * 60L).coerceAtLeast(15L)
    }
}
