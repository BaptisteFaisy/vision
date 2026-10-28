package dev.sam3vision.core.navcore

/**
 * États UI du parcours navigation vocale (spéc. voix § a/b/c : parcours MVP,
 * annonces, cas limites). Pur JVM : l'écran (module `:app`) n'a qu'à lire
 * [overlayTitle], [overlayMessage] et [announcement].
 *
 * Conventions (spéc.) : guidage lancé = overlay vert + voix « Navigation vers
 * X » ; erreur = overlay rouge + voix, jamais overlay seul (sauf panne TTS :
 * [Failed] avec `speak = false`, § c.6) ; hors-trace et GPS perdu = RIEN côté
 * iSee, donc aucun état pour eux ; l'arrivée est annoncée par Maps, pas ici.
 */
sealed interface NavUiState {

    /** Titre de l'overlay (`null` = aucun overlay géré par la navigation). */
    val overlayTitle: String?

    /** Message de l'overlay (`null` = aucun). */
    val overlayMessage: String?

    /**
     * Phrase à annoncer à la voix (`null` = silence — état normal, la nouveauté
     * seule parle).
     */
    val announcement: String?

    /** Accueil : rien d'affiché, rien de dit. */
    data object Idle : NavUiState {
        override val overlayTitle: String? = null
        override val overlayMessage: String? = null
        override val announcement: String? = null
    }

    /**
     * Appui long en cours. Overlay « J'écoute » possédé par la dictée
     * (`DictationFeature`) : cet état ne porte aucun texte (zéro doublon).
     */
    data object Listening : NavUiState {
        override val overlayTitle: String? = null
        override val overlayMessage: String? = null
        override val announcement: String? = null
    }

    /**
     * Transcription ASR en cours. Comme [Listening], l'overlay appartient à
     * la dictée : aucun texte ici.
     */
    data object Transcribing : NavUiState {
        override val overlayTitle: String? = null
        override val overlayMessage: String? = null
        override val announcement: String? = null
    }

    /** Guidage lancé vers [destination] : overlay + « Navigation vers X ». */
    data class Navigating(val destination: String) : NavUiState {
        override val overlayTitle: String? get() = NavMessages.OVERLAY_TITLE
        override val overlayMessage: String? get() = NavMessages.started(destination)
        override val announcement: String? get() = overlayMessage
    }

    /** Guidage éteint : fiche lieu ouverte, « Voici la fiche de X ». */
    data class PlaceCard(val destination: String) : NavUiState {
        override val overlayTitle: String? get() = NavMessages.OVERLAY_TITLE
        override val overlayMessage: String? get() = NavMessages.placeOpened(destination)
        override val announcement: String? get() = overlayMessage
    }

    /**
     * Maps absent : itinéraire ouvert dans le navigateur. L'annonce reste
     * « Navigation vers X » (elle dit ce qui a été ouvert, § b).
     */
    data class BrowserFallback(val destination: String) : NavUiState {
        override val overlayTitle: String? get() = NavMessages.OVERLAY_TITLE
        override val overlayMessage: String? get() = NavMessages.BROWSER_FALLBACK
        override val announcement: String? get() = NavMessages.started(destination)
    }

    /** Ordre STOP consommé : destination oubliée, retour accueil. */
    data object Stopped : NavUiState {
        override val overlayTitle: String? get() = NavMessages.OVERLAY_TITLE
        override val overlayMessage: String? get() = NavMessages.STOP_DONE
        override val announcement: String? get() = NavMessages.STOP_DONE
    }

    /**
     * Échec affichable ([NavError] déjà convertie en texte). Overlay rouge
     * ([NavMessages.ERROR_TITLE]) + voix, sauf `speak = false`
     * (panne TTS : on ne parle pas d'une panne de voix à la voix, § c.6 —
     * le lancement Maps a quand même lieu).
     */
    data class Failed(val message: String, val speak: Boolean = true) : NavUiState {
        override val overlayTitle: String? get() = NavMessages.ERROR_TITLE
        override val overlayMessage: String? get() = message.trim()
        override val announcement: String? get() = if (speak) overlayMessage else null
    }
}
