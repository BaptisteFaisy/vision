package dev.sam3vision.core.navcore

/**
 * Catalogue central des messages utilisateur français de la navigation vocale.
 *
 * Source canonique côté `:core` (pur JVM, testable sans Android) des phrases du
 * contrat UX (spéc. voix § b/e) : chaque texte est verrouillé par `NavMessagesTest`.
 * Les clés `nav_*` de `strings.xml` et `NavigationPhrases` (module `:app`) portent
 * les mêmes textes à l'identique — ce catalogue est la référence vers laquelle
 * les futurs rédacteurs `:app` convergent, sans en dépendre ici.
 *
 * Règles reprises de la spéc. : une seule voix à la fois, erreurs toujours
 * vocales + overlay (jamais overlay seul), langue des phrases = langue de la
 * voix. Les overlays « J'écoute » / « Transcription » restent propriété de la
 * dictée (`DictationFeature`) et ne sont pas dupliqués ici.
 */
object NavMessages {

    /** Titre des overlays de navigation (« Navigation vers X », fiche, erreur…). */
    const val OVERLAY_TITLE = "Navigation"

    /** Titre des overlays d'erreur (rouge). */
    const val ERROR_TITLE = "Erreur"

    /**
     * « Navigation vers X » — [destination] brute, espaces rognés.
     * Identique à `NavigationPhrases.started` et `nav_confirm_destination`.
     * L'appelant refuse les destinations blanches avant de parler.
     */
    fun started(destination: String): String = "Navigation vers ${destination.trim()}"

    /**
     * Guidage impossible à ouvrir (aucune app capable, lancement refusé…).
     * Identique à `NavigationPhrases.GUIDANCE_UNAVAILABLE` et
     * `nav_guidance_unavailable`.
     */
    const val GUIDANCE_UNAVAILABLE = "Je ne peux pas ouvrir le guidage"

    /**
     * « Voici la fiche de X » — guidage éteint, fiche lieu ouverte sans guidage.
     * Future clé `nav_place_opened`.
     */
    fun placeOpened(destination: String): String = "Voici la fiche de ${destination.trim()}"

    /** Overlay seul quand Maps est absent (repli navigateur). Future clé `nav_browser_fallback`. */
    const val BROWSER_FALLBACK = "Itinéraire ouvert dans le navigateur"

    /** Ordre STOP consommé : destination oubliée, retour accueil. Future clé `nav_stop_done`. */
    const val STOP_DONE = "Guidage arrêté"

    /**
     * Micro pris par la vidéo au moment de l'appui long.
     * Reprise à l'identique du libellé `DictationFeature` (casse d'origine
     * conservée) : un seul texte, pas de doublon. Future clé `nav_mic_busy_video`.
     */
    const val MIC_BUSY_VIDEO = "micro déjà occupé par la vidéo"

    /**
     * Aide sous l'interrupteur « Guider vers la destination confirmée »
     * (texte en dur du composable, comme les autres aides).
     */
    const val SETTING_OFF_HELP = "Éteint : iSee montre la fiche du lieu au lieu de lancer le guidage."

    /**
     * Double échec ASR (Meta puis Scribe) : « Erreur. \<msg Meta\>. \<msg Scribe\>. »
     * (spéc. § c.5). Affiché en overlay rouge ET lu à la voix ; la destination
     * n'est jamais inventée.
     */
    fun asrBothFailed(primary: String, fallback: String): String =
        "Erreur. ${cleanClause(primary)}. ${cleanClause(fallback)}."

    private fun cleanClause(clause: String): String =
        clause.trim().trimEnd('.').trim().ifBlank { "échec inconnu" }
}
