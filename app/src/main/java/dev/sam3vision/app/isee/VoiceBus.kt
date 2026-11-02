package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig

/** Étiquette des diagnostics du mutisme spontané (`logcat -s VoiceMute:*`). */
private const val TAG_MUTE = "VoiceMute"

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
     * clip [clipId] d'abord, synthèse réseau sinon (tous niveaux).
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
 *
 * Exception (ordre user du 02/11/2026, VOIX-DANGER § 42) : les escaliers
 * (niveau 6, compte des marches) sont prioritaires sur tout — ils coupent
 * la parole en cours (voitures, humains, bordures, chat compris) et vont
 * au bout, personne ne les coupe (ni le niveau 0 re-posté à chaque frame,
 * ni la Traversée 100+, ni les niveaux 2 à 5) — sauf un mot de guidage en
 * cours (GPS 3/11, incoupable lui aussi : respect mutuel, § 41 — une
 * manœuvre sous un compte est reportée au silence, jamais perdue).
 * Seule la parole du user (niveau 1, micro tenu) passe devant : les
 * escaliers l'attendent. Entre deux comptes (1 s), l'esquive (mots
 * continus humains, repli 12) re-poste et parle dans le silence — au
 * milieu du cycle, jamais par-dessus le compte. Le chat, lui, attend
 * la fin des marches ([stairsActive]) : une tranche ne tient pas dans
 * l'intervalle.
 *
 * Exception (ordre user du 02/11/2026) : un mot de guidage en cours
 * (manœuvre 11, rappel 3 — clips offline ≤0,5 s) va au bout lui aussi :
 * tout le monde attend la fin du mot sauf la parole user (1, le micro
 * garde son silence), re-posts GPS compris (un « left » frais ne coupe
 * pas le « left » en cours : la redite 15 s couvre le tour perdu).
 */
object VoiceBus {
    /** Aucune parole en cours : tout niveau parle. */
    private const val IDLE_LEVEL = Int.MAX_VALUE

    /** Porte-parole câblé (`null` = rien de branché : les vœux tombent). */
    @Volatile
    var speaker: VoiceBusSpeaker? = null

    /**
     * Rappel de repos (`null` = personne : le chat ne reprend pas tout seul).
     * Appelé hors verrou à chaque retour au repos réel (fin de lecture,
     * fin de parole user, fin de lecture chat) : le ViewModel y relance la
     * réponse coupée (reprise au rang 9, hors Traversée). Le rappel tourne
     * sous le verrou du bus (réentrant) : il ne fait que lancer une lecture.
     */
    @Volatile
    var onIdle: (() -> Unit)? = null

    /** Niveau de la parole en cours ([IDLE_LEVEL] = aucune). */
    private var activeLevel: Int = IDLE_LEVEL

    /** Horodatage de la dernière parole acceptée (repli silence). */
    private var lastSayMs: Long = 0L

    /** Horodatage du dernier compte accepté (le chat attend la fin, pas le silence). */
    private var lastStairsSayMs: Long = 0L

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
        if (text.isBlank()) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=vide level=$level")
            return
        }
        val target = speaker
        if (target == null) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=sans-porte-parole level=$level texte=[$text]")
            return
        }
        if (userHoldsMic && level != OutdoorLevel.CAR_MOVING) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=micro-user level=$level texte=[$text]")
            return
        }
        val nowMs = System.currentTimeMillis()
        if (stairsBusStuck(activeLevel, nowMs, lastSayMs)) activeLevel = IDLE_LEVEL
        if (guidanceSpeaking() && level != OutdoorLevel.USER_SPEECH) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=mot-guidage-en-cours level=$level texte=[$text]")
            return
        }
        if (level == OutdoorLevel.STAIRS) {
            activeLevel = level
            lastSayMs = nowMs
            lastStairsSayMs = nowMs
            target.say(level, text, clipId, clips)
            return
        }
        if (activeLevel == OutdoorLevel.STAIRS) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=escaliers-en-cours level=$level texte=[$text]")
            return
        }
        if (activeLevel != IDLE_LEVEL && level > activeLevel) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=ecrase level=$level actif=$activeLevel chat=$chatReads texte=[$text]")
            return
        }
        activeLevel = level
        lastSayMs = nowMs
        target.say(level, text, clipId, clips)
    }

    /**
     * Vrai quand des escaliers parlent (niveau 6 en cours) : les producteurs
     * s'y taisent en amont (sans consommer leur tour d'antirépétition —
     * [offer] et les guetteurs directs) au lieu d'être jetés par [post].
     */
    @Synchronized
    fun stairsSpeaking(): Boolean = activeLevel == OutdoorLevel.STAIRS

    /**
     * Vrai quand un mot de guidage parle (manœuvre 11, rappel 3 — ≤0,5 s) :
     * les producteurs frame s'y taisent en amont (même motif que
     * [stairsSpeaking]) au lieu d'être jetés par [post].
     */
    @Synchronized
    fun guidanceSpeaking(): Boolean =
        activeLevel == OutdoorLevel.GPS_EVENT || activeLevel == OutdoorLevel.GPS_REMINDER

    /**
     * Vrai quand les escaliers sont actifs à [nowMs] : compte en cours, ou
     * dernier compte il y a moins de [STAIRS_QUIET_MS] (le chat attend la
     * fin des marches, pas le prochain silence — une tranche ne tient pas
     * dans l'intervalle).
     */
    @Synchronized
    fun stairsActive(nowMs: Long): Boolean =
        stairsSpeaking() || nowMs - lastStairsSayMs < STAIRS_QUIET_MS

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
        if (userHoldsMic || chatReads) return
        if (activeLevel == IDLE_LEVEL) return
        activeLevel = IDLE_LEVEL
        onIdle?.invoke()
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
            onIdle?.invoke()
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
        if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-chat lit=$speaking actif=$activeLevel")
        chatReads = speaking
        if (speaking) {
            activeLevel = OutdoorLevel.CHAT
        } else if (activeLevel == OutdoorLevel.CHAT) {
            activeLevel = IDLE_LEVEL
            onIdle?.invoke()
        }
    }

    /** Oublie la parole en cours (nouvelle séance, tests) : le câblage reste. */
    @Synchronized
    fun reset() {
        activeLevel = IDLE_LEVEL
        userHoldsMic = false
        chatReads = false
        lastSayMs = 0L
        lastStairsSayMs = 0L
    }
}

/**
 * Bus bloqué sur des escaliers : niveau 6 en cours depuis plus de
 * [STAIRS_BUS_TTL_MS] sans parole acceptée (lecteur pendu, synthèse
 * jamais revenue — sinon chaque compte rafraîchit l'horodatage).
 * Sans cette garde, un niveau 6 orphelin tairait toute la voix,
 * les forts comme les doux. Pur, testé en JVM.
 */
internal fun stairsBusStuck(activeLevel: Int, nowMs: Long, lastSayMs: Long): Boolean =
    activeLevel == OutdoorLevel.STAIRS && nowMs - lastSayMs > STAIRS_BUS_TTL_MS

/** Silence maximal sous un niveau 6 (4 s : 4 comptes manqués, jamais en direct normal). */
internal const val STAIRS_BUS_TTL_MS = 4_000L

/** Fin des marches : 2 s sans compte accepté (2 cycles manqués, le compte bat à 1 s). */
internal const val STAIRS_QUIET_MS = 2_000L
