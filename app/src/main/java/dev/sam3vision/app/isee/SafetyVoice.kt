package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.core.settings.Settings

/**
 * Voix de sécurité d'iSee : les alertes vitales (bordure à 0,5 m —
 * « Bordure à 0,5 mètre. ») parlent ici, pas par la voix ElevenLabs.
 *
 * Trois garanties (ordre user du 30/10/2026) :
 * - immédiat : le TTS système parle sans synthèse réseau ni attente de clé
 *   — là où une synthèse ElevenLabs met des secondes, l'alerte part aussitôt ;
 * - coupe tout : la lecture ElevenLabs en cours (chat, guidage doux) est
 *   arrêtée avant de parler ;
 * - survit à la dictée : moteur dédié, parlé sans condition de silence — ni
 *   la parole du user (tenue, transcription), ni `silenceForGlasses` (qui
 *   coupe le lecteur ElevenLabs et la voix route, pas ce moteur) ne taisent
 *   une alerte.
 *
 * Priorité absolue (31/10/2026) : la nouvelle alerte coupe l'alerte en cours
 * (flush toujours) au lieu d'attendre sa fin — un par un, la plus fraîche gagne.
 * Hors-ligne, parle quand même (voix système embarquée, aucune clé).
 *
 * L'alerte est orale seule : aucun fanion parlant n'est posé (le TTS système
 * ne rend pas de fin de lecture, un fanion sans fin bloquerait la dictée).
 * Mais [stopCurrent] rend le fanion de la lecture coupée : `player.stop()`
 * ne termine jamais (`onComplete` ne part pas), le garder muterait toute la
 * voix douce (lecteur cru occupé pour toujours).
 *
 * Pur Android (aucune coroutine, moteur injecté) : testé en JVM derrière un
 * pilote faux. [stopCurrent] coupe la lecture ElevenLabs et rend son fanion
 * (`player.stop()` + `cutSpeaking()` en prod, faux en test).
 */
class SafetyVoice(
    player: SpeechPlayer,
    private val system: SystemTtsEngine,
    private val settings: () -> Settings,
    private val stopCurrent: () -> Unit = player::stop,
) {

    /** Parle [phrase] aussitôt : coupe tout, puis TTS système en flush (jamais de file). */
    fun speak(phrase: String) {
        if (phrase.isBlank()) return
        stopCurrent()
        val current = settings()
        system.speak(phrase, true, SystemTtsEngine.localeOf(current.speechLanguage), current.ttsSpeed)
    }

    /** Libération finale (moteur TTS système). */
    fun shutdown() = system.close()
}
