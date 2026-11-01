package dev.sam3vision.core.settings

/**
 * Role of this phone: the accompanied person's device ([Blind], iSee) or
 * the companion's device ([Caregiver], the loved one's tracking page).
 *
 * The role removes no app function: it only picks the **home
 * page** — iSee, or the Caregiver page following the loved one's position.
 * The default stays [Blind]: a fresh install opens iSee as before.
 */
enum class CaregiverRole(
    /** Settings label, the one the user picks. */
    val label: String,
) {
    Blind("Blind person"),
    Caregiver("Caregiver"),
    ;

    companion object {
        /** Persisted name → role; unknown or missing value ⇒ [Blind] (default). */
        fun fromName(name: String?): CaregiverRole =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Blind
    }
}
