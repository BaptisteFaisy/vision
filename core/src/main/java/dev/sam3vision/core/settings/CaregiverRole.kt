package dev.sam3vision.core.settings

/**
 * Rôle de ce téléphone : appareil de la personne accompagnée ([Blind], iSee) ou
 * appareil de l'accompagnant ([Caregiver], page de suivi du proche).
 *
 * Le rôle ne retire aucune fonction à l'app : il choisit seulement la **page
 * d'accueil** — iSee, ou la page Caregiver qui suit la position du proche. Le
 * défaut reste [Blind] : une installation neuve ouvre iSee comme avant.
 */
enum class CaregiverRole(
    /** Libellé des Réglages, celui que l'utilisateur choisit. */
    val label: String,
) {
    Blind("Blind person"),
    Caregiver("Caregiver"),
    ;

    companion object {
        /** Nom persisté → rôle ; valeur inconnue ou absente ⇒ [Blind] (défaut). */
        fun fromName(name: String?): CaregiverRole =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Blind
    }
}
