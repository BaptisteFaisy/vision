package dev.sam3vision.app.isee

/**
 * Porte-parole du [VoiceBus] : le câblage applicatif (une seule implémentation,
 * posée au démarrage — voir le câblage du ViewModel). [idle] vrai = le lecteur
 * partagé est libre ; [say] parle (coupe le lecteur partagé puis joue, en
 * coroutine — le bus ne suspend jamais) ; [cut] coupe la lecture en cours et
 * rend son fanion.
 */
interface VoiceBusSpeaker {
    /** Vrai si rien ne parle sur le lecteur partagé (non bloquant). */
    fun idle(): Boolean

    /** Parle [text] (clip [clipId] d'abord, synthèse sinon) au niveau [level]. */
    fun say(level: Int, text: String, clipId: String?)

    /** Coupe la lecture en cours (parole du user, préemption). */
    fun cut()
}

/**
 * Bus vocal SPEC v4, contrat partagé : `fun VoiceBus.post(level, text, clipId?)`.
 *
 * Écrasement pur : le plus petit nombre gagne. Au repos, tout niveau parle ;
 * pendant une parole, seul un niveau plus petit (ou égal — même source
 * re-détectée, dernier gagnant) coupe et parle ; l'écrasé n'est jamais redit
 * (pas de file — sauf le chat, niveau 9, qui reprend depuis la coupure au
 * lieu de perdre). Échelle dehors 0 à 12 ([OutdoorLevel]), Traversée 100 à
 * 102 ([CrossingLevel]).
 *
 * La parole du user (niveau 1, [userSpeaking]) tait tout sauf le niveau 0
 * (seul 0 la coupe) ; la lecture du chat ([chatSpeaking]) tient le niveau 9
 * (un niveau 12 ne coupe jamais une réponse en cours). L'antirépétition vit
 * en amont ([dev.sam3vision.core.live.AntiRepeat], côté producteurs) : le bus n'arbitre que l'instant.
 */
object VoiceBus {
    /** Aucune parole en cours : tout niveau parle. */
    private const val IDLE_LEVEL = Int.MAX_VALUE

    /** Porte-parole câblé (`null` = rien de branché : les vœux tombent). */
    @Volatile
    var speaker: VoiceBusSpeaker? = null

    /** Niveau de la parole en cours ([IDLE_LEVEL] = aucune). */
    private var activeLevel: Int = IDLE_LEVEL

    /** Le user tient le micro (niveau 1) : seul le niveau 0 passe. */
    private var userHoldsMic = false

    /** Le chat lit une réponse (niveau 9). */
    private var chatReads = false

    /**
     * Propose [text] au niveau [level] (clip [clipId] si connu) : parlé
     * aussitôt si rien ne parle ou si [level] écrase la parole en cours,
     * jeté sinon (jamais redit, jamais mis en file).
     */
    @Synchronized
    fun post(level: Int, text: String, clipId: String? = null) {
        if (text.isBlank()) return
        val target = speaker ?: return
        if (userHoldsMic && level != OutdoorLevel.CAR_MOVING) return
        if (!target.idle() && level > activeLevel) return
        activeLevel = level
        target.say(level, text, clipId)
    }

    /**
     * Parole du user : en commençant, coupe la lecture en cours (chat
     * compris) et tait tout sauf le niveau 0 ; en finissant, rend la parole
     * (sauf si un niveau 0 a parlé entre-temps : il garde la main).
     */
    @Synchronized
    fun userSpeaking(speaking: Boolean) {
        if (speaking == userHoldsMic) return
        userHoldsMic = speaking
        if (speaking) {
            speaker?.cut()
            activeLevel = OutdoorLevel.USER_SPEECH
        } else if (activeLevel == OutdoorLevel.USER_SPEECH) {
            activeLevel = IDLE_LEVEL
        }
    }

    /**
     * Lecture du chat : tient le niveau 9 tant qu'une réponse se lit (le
     * chat reprend depuis la coupure au lieu de perdre, côté lecture) ;
     * en fin, rend la parole (sauf préemption entre-temps).
     */
    @Synchronized
    fun chatSpeaking(speaking: Boolean) {
        if (speaking == chatReads) return
        chatReads = speaking
        if (speaking) {
            activeLevel = OutdoorLevel.CHAT
        } else if (activeLevel == OutdoorLevel.CHAT) {
            activeLevel = IDLE_LEVEL
        }
    }

    /** Oublie la parole en cours (nouvelle séance, tests) : le câblage reste. */
    @Synchronized
    fun reset() {
        activeLevel = IDLE_LEVEL
        userHoldsMic = false
        chatReads = false
    }
}
