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

    /**
     * Parle [text] au niveau [level] : séquence [clips] si non vide, sinon
     * clip [clipId] d'abord, synthèse GPS sinon (silence pour les autres
     * niveaux sans clip).
     */
    fun say(level: Int, text: String, clipId: String?, clips: List<String>)

    /** Coupe la lecture en cours (parole du user, préemption). */
    fun cut()
}

/**
 * Bus vocal SPEC v4, contrat partagé : `fun VoiceBus.post(level, text, clipId?, clips)`.
 *
 * Écrasement pur : le plus petit nombre gagne. Au repos, tout niveau parle ;
 * pendant une parole, seul un niveau plus petit (ou égal — même source
 * re-détectée, dernier gagnant) coupe et parle ; l'écrasé n'est jamais redit
 * (pas de file — sauf le chat, niveau 9, qui reprend depuis la coupure au
 * lieu de perdre). Échelle dehors 0 à 12 ([OutdoorLevel]), Traversée 100 à
 * 102 ([dev.sam3vision.core.crossing.CrossingLevels]).
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

    /** Horodatage de la dernière parole acceptée (repli silence). */
    private var lastSayMs: Long = 0L

    /** Le user tient le micro (niveau 1) : seul le niveau 0 passe. */
    private var userHoldsMic = false

    /** Le chat lit une réponse (niveau 9). */
    private var chatReads = false

    /**
     * Propose [text] au niveau [level] (clip [clipId] si connu, séquence
     * [clips] si non vide) : parlé aussitôt si rien ne parle ou si [level]
     * écrase la parole en cours, jeté sinon (jamais redit, jamais mis en
     * file). La décision lit [activeLevel], pas le lecteur (sinon une parole
     * décidée mais pas encore jouée laisserait passer un niveau plus bas).
     */
    @Synchronized
    fun post(level: Int, text: String, clipId: String? = null, clips: List<String> = emptyList()) {
        if (text.isBlank()) return
        val target = speaker ?: return
        if (userHoldsMic && level != OutdoorLevel.CAR_MOVING) return
        if (activeLevel != IDLE_LEVEL && level > activeLevel) return
        activeLevel = level
        lastSayMs = System.currentTimeMillis()
        target.say(level, text, clipId, clips)
    }

    /**
     * Silence écoulé depuis la dernière parole acceptée (repli 12 : parler
     * seulement après 300 ms sans rien).
     */
    @Synchronized
    fun silenceMs(nowMs: Long): Long = (nowMs - lastSayMs).coerceAtLeast(0L)

    /**
     * Fin de lecture (appelée par le lecteur) : rend la parole, sauf si le
     * user tient le micro ou si le chat lit (ils gardent leur niveau).
     */
    @Synchronized
    fun onSpeechIdle() {
        if (!userHoldsMic && !chatReads) activeLevel = IDLE_LEVEL
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
        lastSayMs = 0L
    }
}
