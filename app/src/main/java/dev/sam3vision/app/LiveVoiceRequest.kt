package dev.sam3vision.app

/**
 * Ordres vocaux vers le direct, posés depuis l'appui long iSee, consommés au
 * démarrage du direct (comme [HomePresetRequest]) : chasse picto, création
 * en attente de frame, démo. Usage unique : une rotation ne rejoue pas.
 */
object LiveVoiceRequest {

    /** Demande en attente : chasse, création, démo (combinables). */
    data class Request(
        /** Mot à guider vers un repère armé (`null` = pas de chasse). */
        val pictoHunt: String? = null,
        /** Label à créer dès la première frame (`null` = pas de création). */
        val pictoCreate: String? = null,
        /** Vrai = démo picto active au démarrage. */
        val pictoDemo: Boolean = false,
    )

    private var pending: Request? = null

    /** Pose [request] pour le prochain démarrage du direct (écrase la précédente). */
    fun push(request: Request) {
        pending = request
    }

    /** Demande posée, `null` si aucune (lecture unique, comme [RouteRequest]). */
    fun consume(): Request? {
        val request = pending
        pending = null
        return request
    }
}
