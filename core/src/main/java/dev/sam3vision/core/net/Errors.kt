package dev.sam3vision.core.net

/**
 * Erreurs réseau typées, avec un message directement affichable dans l'UI.
 * [InvalidKey] = 401, [RateLimited] = 429, [Timeout] = délai dépassé,
 * [Network] = backend injoignable, [Http] = autre code, [BadResponse] = corps illisible.
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class InvalidKey(message: String = "invalid key (401)") : ApiError(message)

    class RateLimited(message: String = "limit reached (429)") : ApiError(message)

    /**
     * 402 : compte sans crédits ou facturation non configurée (Meta ASR), ou voix de
     * bibliothèque ElevenLabs refusée en plan free — prendre alors une voix premade.
     */
    class PaymentRequired(
        message: String = "payment required (402): account out of credits or billing not " +
            "configured (ElevenLabs library voice? take a premade one, e.g. Sarah)",
    ) : ApiError(message)

    class Timeout(message: String = "backend unreachable: timeout") : ApiError(message)

    class Network(
        message: String = "backend unreachable: network",
        cause: Throwable? = null,
    ) : ApiError(message, cause)

    /**
     * 404 « invalid function call » renvoyé par le proxy Modal : l'app **n'est plus déployée**
     * (quelqu'un l'a arrêtée), ce n'est pas une route manquante. Inutile de réessayer :
     * il faut la redéployer. Le message parle à l'utilisateur du téléphone, pas à un shell.
     */
    class BackendStopped(
        message: String = "SAM GPU off — ask b to turn it on",
    ) : ApiError(message)

    class Http(val code: Int, val body: String) :
        ApiError("HTTP error $code" + if (body.isBlank()) "" else ": ${body.take(160)}")

    companion object {
        /**
         * Vrai si l'erreur est un 404 de **route manquante** sur un backend vivant
         * (ex. `POST /video` quand le serveur ne l'expose pas) : le repli photo vers
         * `/segment` est alors pertinent. Faux pour [BackendStopped] (GPU éteint :
         * `/segment` serait éteint aussi) et pour tout autre échec.
         */
        fun isRouteMissing(error: Throwable): Boolean = error is Http && error.code == 404
    }

    class BadResponse(detail: String) : ApiError("unexpected response: $detail")

    /** Le moteur principal et son repli ont tous deux échoué (messages déjà rédigés). */
    class AllProviders(detail: String) : ApiError("dictation failed: $detail")

    /** Opération que ce client ne sait pas rendre (ex. question sans image) : rien à réessayer. */
    class NotSupported(message: String) : ApiError(message)
}
