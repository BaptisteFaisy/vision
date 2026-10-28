package dev.sam3vision.app.room

import androidx.room.RoomDatabase

/**
 * Repli downgrade partagé par toutes les bases Room de l'app (média, nettoyage).
 *
 * Quand un téléphone revient d'un build au schéma plus récent vers un schéma plus
 * ancien (build mission puis retour), Room reçoit un downgrade (ex. 2 → 1) : sans
 * repli, l'ouverture lève `IllegalStateException: A migration from 2 to 1 was
 * required but not found` et l'app crashe au démarrage — vu 9 fois sur l'appareil
 * le 27/10. Avec ce repli, Room recrée la base vide au lieu de crasher.
 *
 * Les montées de version sans migration restent des crashs volontaires (vraie
 * migration exigée) : seul le downgrade est destructif. Journal WAL : les lectures
 * ne bloquent plus les écritures.
 *
 * Chaque base justifie la perte acceptée dans son propre commentaire (voir
 * `CleanupDatabase.build`, `MediaDatabase.build`).
 */
object RepliDowngrade {

    /** Applique le repli au [builder] : destruction au downgrade, journal WAL. */
    fun <T : RoomDatabase> appliquer(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> =
        builder
            .fallbackToDestructiveMigrationOnDowngrade()
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
}
