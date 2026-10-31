package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Les trois mots de rappel de suivi du chemin (ordre user du 31/10) : quand
 * iSee n'a plus rien de neuf à dire sur voie libre, le rappel redit comment
 * suivre le chemin (« Continue straight. »), en alternance avec le mot de la
 * consigne (« Straight. ») — chaque émission reste ainsi distincte de la
 * précédente, là où les déduplications aval (`LiveProbeState.spoken`,
 * `ProximityVoice.lastPhrase`) avaleraient une répétition à l'identique.
 *
 * Voix SAM douce, comme les mots ([GuidanceWord]) : attend le silence, ne
 * coupe jamais rien. Le rappel ne contredit jamais la consigne stable (même
 * direction : tout droit, retour ou décalage) — il la reformule. Le bouché
 * n'a pas de rappel : un ordre d'évitement ne se rabâche pas.
 *
 * Hors catalogue embarqué (`tools/voicepack-catalog.json`, garde
 * `tools/check-voicepack.py` inchangée : elle ne contrôle que le
 * catalogue) : première occurrence synthétisée réseau puis mise en cache
 * (`ProximityVoice.speakWord`, `VoicePack.put`, 30 j), rejouée en clip
 * ensuite. Le jour où le pack les embarque, ajouter les six MP3 + les
 * entrées catalogue, sans toucher ce fichier.
 */
enum class ContinueWord {
    Straight,
    Left,
    Right,
    ;

    /** Rappel prononcé, langue de la voix. */
    fun text(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.En -> when (this) {
            Straight -> "Continue straight."
            Left -> "Continue left."
            Right -> "Continue right."
        }
        else -> when (this) {
            Straight -> "Continue tout droit."
            Left -> "Continue à gauche."
            Right -> "Continue à droite."
        }
    }

    /** Entrée cache du rappel (`vp_<id>_<lang>.mp3`, synthèse runtime). */
    val clipId: String get() = "guide_continue_" + name.lowercase()

    companion object {
        /**
         * Rappel de la consigne stable, `null` pour [GuidanceWord.Stop] : le
         * stop est un bouché, jamais une voie libre — il ne se rappelle pas.
         */
        fun fromGuidance(word: GuidanceWord): ContinueWord? = when (word) {
            GuidanceWord.Straight -> Straight
            GuidanceWord.Left -> Left
            GuidanceWord.Right -> Right
            GuidanceWord.Stop -> null
        }

        /**
         * Entrée cache d'une phrase parlée, `null` si ce n'est pas un rappel
         * (mots SAM, TwinLite, positions : autre canal).
         */
        fun clipIdFor(phrase: String): String? = when (phrase) {
            "Continue straight.", "Continue tout droit." -> Straight.clipId
            "Continue left.", "Continue à gauche." -> Left.clipId
            "Continue right.", "Continue à droite." -> Right.clipId
            else -> null
        }
    }
}
