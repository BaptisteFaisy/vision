package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.live.AntiRepeat

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
 * pendant une parole, seul un niveau plus petit (ou égal — dernier gagnant)
 * coupe et parle ; l'écrasé n'est jamais redit
 * (pas de file — sauf le chat, niveau 9, qui reprend depuis la coupure au
 * lieu de perdre, et les escaliers, niveau 6, qui ont une place unique,
 * § ci-dessous). Échelle dehors 0 à 12 ([OutdoorLevel]), Traversée 100 à
 * 102 ([dev.sam3vision.core.crossing.CrossingLevels]).
 *
 * La parole du user (niveau 1, [userSpeaking]) tait tout sauf le niveau 0
 * (seul 0 la coupe) ; la lecture du chat ([chatSpeaking]) tient le niveau 9
 * (un niveau 12 ne coupe jamais une réponse en cours). L'antirépétition vit
 * en amont ([dev.sam3vision.core.live.AntiRepeat], côté producteurs) : le bus n'arbitre que l'instant.
 *
 * Exception (ordre user du 02/11/2026, VOIX-DANGER § 42) : les escaliers
 * (niveau 6) sont prioritaires sur tout — ils coupent la parole en cours
 * (voitures, humains, bordures, chat et mots de guidage compris) et vont
 * au bout, personne ne les coupe (ni le niveau 0 re-posté à chaque frame,
 * ni la Traversée 100+, ni les niveaux 2 à 5). Seule la parole du user
 * (niveau 1, micro tenu) passe devant : les escaliers l'attendent. Entre
 * deux comptes (1 s), l'esquive (mots continus humains, repli 12) re-poste
 * et parle dans le silence — au milieu du cycle, jamais par-dessus le
 * compte. Le chat attend la fin des marches ([stairsActive]).
 *
 * Exception (ordre user du 02/11/2026) : un mot de guidage en cours
 * (manœuvre 11, rappel 3 — clips offline ≤0,5 s) va au bout devant tous
 * sauf la parole user (1), la bordure (5) et les escaliers (6), qui le
 * coupent (re-posts GPS compris : un « left » frais ne coupe pas le
 * « left » en cours, la redite 15 s couvre le tour perdu).
 *
 * Exception à l'exception (ordre user du 02/11/2026, bordures) : la
 * bordure (niveau 5) coupe un mot de guidage en cours (elle interrompt
 * « droite » / « gauche », pas l'inverse) et n'est jamais coupée par eux
 * (le rappel 3 attend la fin de la bordure ; manœuvre 11 et repli 12
 * sont déjà sous elle). Voitures (0), parole user (1), humains (2) et
 * escaliers (6, compte incoupable) gardent leur rang devant elle.
 *
 * Exception (ordre user du 03/11/2026, VOIX-DANGER § 44) : une phrase
 * d'escaliers n'est jamais coupée par une autre phrase d'escaliers — elle
 * attend en place unique (dernier gagnant, TTL [STAIRS_PENDING_TTL_MS])
 * et joue à la fin de la courante ([onSpeechIdle]).
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

    /**
     * Jeton de la parole en cours (+1 par parole acceptée) : le superviseur
     * de restitution ([BusSpeaker]) ne rend le bus que si le niveau ET le
     * jeton sont encore les siens — une parole plus récente ne se fait
     * jamais voler son tour.
     */
    private var holdToken: Long = 0L

    /** Horodatage de la dernière parole acceptée (repli silence). */
    private var lastSayMs: Long = 0L

    /** Horodatage du dernier compte accepté (le chat attend la fin, pas le silence). */
    private var lastStairsSayMs: Long = 0L

    /**
     * Phrase escaliers en attente de fin de la phrase courante (niveau 6
     * seulement, dernier gagnant) : une phrase de marches ne se coupe jamais
     * elle-même — ordre user du 03/11, « il se coupe en permanence et
     * n'arrive pas à dire le nombre de marches ».
     */
    private var pendingStairs: PendingStairs? = null

    /** Phrase escaliers retenue : texte, clip, chaîne, instant et clé d'antirépétition. */
    private data class PendingStairs(
        val text: String,
        val clipId: String?,
        val clips: List<String>,
        val atMs: Long,
        val key: String?,
    )

    /**
     * Horloge du bus (`System.currentTimeMillis` en prod ; temps virtuel en
     * test JVM). [reset] la rend d'origine : un test ne fuit pas dans le suivant.
     */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Le user tient le micro (niveau 1) : seul le niveau 0 passe. */
    private var userHoldsMic = false

    /** Le chat lit une réponse (niveau 9). */
    private var chatReads = false

    /**
     * Propose [text] au niveau [level] (clip [clipId] si connu, séquence
     * [clips] si non vide) : parlé aussitôt si rien ne parle ou si [level]
     * écrase la parole en cours, retenu (escaliers, place unique) ou jeté
     * sinon (jamais redit). La décision lit [activeLevel], pas le lecteur
     * (sinon une parole décidée mais pas encore jouée laisserait passer un
     * niveau plus bas).
     * [antiRepeatKey] rend son tour quand l'annonce est jetée (SPEC v4 § 5 :
     * une annonce avalée ne consomme pas son tour — le producteur l'avait
     * consommé par son pré-contrôle) ; `null` = sans clé (comportement
     * inchangé : GPS, chat, repli).
     *
     * Rend **vrai quand la phrase est retenue et émise**, faux quand elle a
     * été jetée (vide, sans porte-parole, micro user, mot de guidage,
     * niveau écrasé) : depuis le 02/11/2026 l'appelant sait donc
     * s'il a parlé, au lieu de le supposer (A9 § 3 D8 : le verdict traversée
     * croyait avoir parlé alors que le bus l'avait jeté ⇒ jusqu'à 2 s de
     * silence, « Don't cross. » compris). Aucun appelant existant ne casse :
     * ignorer une valeur de retour est légal en Kotlin. Faux aussi si le
     * porte-parole lève (parole fantôme : bus rendu aussitôt, tour rendu —
     * [sayOrGhost], jamais de crash producteur).
     */
    @Synchronized
    fun post(level: Int, text: String, clipId: String? = null, clips: List<String> = emptyList(),
        antiRepeatKey: String? = null): Boolean {
        if (text.isBlank()) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=vide level=$level")
            refundTurn(antiRepeatKey)
            return false
        }
        val target = speaker
        if (target == null) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=sans-porte-parole level=$level texte=[$text]")
            refundTurn(antiRepeatKey)
            return false
        }
        if (userHoldsMic && level != OutdoorLevel.CAR_MOVING) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=micro-user level=$level texte=[$text]")
            refundTurn(antiRepeatKey)
            return false
        }
        val nowMs = clock()
        if (stairsBusStuck(activeLevel, nowMs, lastSayMs)) activeLevel = IDLE_LEVEL
        if (guidanceSpeaking() && level != OutdoorLevel.USER_SPEECH && level != OutdoorLevel.CURB &&
            level != OutdoorLevel.STAIRS) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=mot-guidage-en-cours level=$level texte=[$text]")
            refundTurn(antiRepeatKey)
            return false
        }
        if (level == OutdoorLevel.STAIRS) {
            return postStairs(target, text, clipId, clips, antiRepeatKey, nowMs)
        }
        if (activeLevel == OutdoorLevel.STAIRS) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=escaliers-en-cours level=$level texte=[$text]")
            refundTurn(antiRepeatKey)
            return false
        }
        if (curbGuidanceRefused(level, text, antiRepeatKey)) return false
        if (activeLevel != IDLE_LEVEL && level > activeLevel && !curbPreemptsGuidance(level)) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=ecrase level=$level actif=$activeLevel chat=$chatReads texte=[$text]")
            refundTurn(antiRepeatKey)
            return false
        }
        activeLevel = level
        lastSayMs = nowMs
        holdToken++
        return sayOrGhost(target, level, text, clipId, clips, antiRepeatKey)
    }

    /**
     * Rappel GPS (3) sous une bordure en cours (5) : il attend la fin de
     * la bordure (ordre user du 02/11/2026 — elle interrompt « droite » /
     * « gauche », pas l'inverse ; manœuvre 11 et repli 12 sont déjà sous
     * elle par l'échelle). Vrai = vœu jeté (tour rendu).
     * Appelée sous le verrou de [post].
     */
    private fun curbGuidanceRefused(level: Int, text: String, antiRepeatKey: String?): Boolean {
        if (activeLevel != OutdoorLevel.CURB || level != OutdoorLevel.GPS_REMINDER) return false
        android.util.Log.w(TAG_MUTE, "bus-jet motif=bordure-en-cours level=$level texte=[$text]")
        refundTurn(antiRepeatKey)
        return true
    }

    /**
     * Bordure (5) sur un mot de guidage en cours (manœuvre 11, rappel 3) :
     * elle coupe (même ordre) — l'écrasement d'échelle ne s'applique pas.
     */
    private fun curbPreemptsGuidance(level: Int): Boolean =
        level == OutdoorLevel.CURB && guidanceSpeaking()

    /**
     * Poste une phrase escaliers (niveau 6). Bus libre ou tenu par un autre
     * niveau : elle parle tout de suite (les escaliers coupent tout, § 40).
     * Escaliers déjà en cours : elle est **retenue** en place unique (la plus
     * récente écrase) et parlera à la fin de la phrase courante — une phrase
     * de marches ne se coupe jamais elle-même (ordre user du 03/11 : « il se
     * coupe en permanence et n'arrive pas à dire le nombre de marches » ;
     * compte qui oscille, annonce re-détectée, répétition 1 s et battement
     * 700 ms visaient tous le même niveau 6). Rend vrai dans les deux cas :
     * retenue = elle parlera.
     */
    private fun postStairs(
        target: VoiceBusSpeaker,
        text: String,
        clipId: String?,
        clips: List<String>,
        antiRepeatKey: String?,
        nowMs: Long,
    ): Boolean {
        if (activeLevel == OutdoorLevel.STAIRS) {
            pendingStairs = PendingStairs(text, clipId, clips, nowMs, antiRepeatKey)
            // `lastStairsSayMs` seul : le chat attend la fin des marches, mais
            // `lastSayMs` reste celui de la dernière parole RÉELLE — sinon la
            // garde `stairsBusStuck` (4 s) ne pourrait plus jamais libérer un
            // niveau 6 dont la fin de lecture s'est perdue.
            lastStairsSayMs = nowMs
            if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-escaliers-en-attente texte=[$text]")
            return true
        }
        activeLevel = OutdoorLevel.STAIRS
        lastSayMs = nowMs
        lastStairsSayMs = nowMs
        holdToken++
        return sayOrGhost(target, OutdoorLevel.STAIRS, text, clipId, clips, antiRepeatKey)
    }

    /**
     * Dit [text] via [target] : si [VoiceBusSpeaker.say] lève (porte-parole
     * fautif — le bus est timbré AVANT l'appel, prouvé par le corps), la
     * parole est fantôme (bus tenu, rien ne parle) : le bus est rendu
     * aussitôt (jeton inchangé : encore la nôtre — réentrance sûre) et le
     * tour d'antirépétition rendu (SPEC v4 § 5 : pas vraiment parlée) —
     * faux, jamais de crash producteur. Sobre : un warning par fantôme.
     * Appelée sous le verrou de [post] (comme [refundTurn]).
     */
    private fun sayOrGhost(
        target: VoiceBusSpeaker,
        level: Int,
        text: String,
        clipId: String?,
        clips: List<String>,
        antiRepeatKey: String?,
    ): Boolean {
        val token = holdToken
        try {
            target.say(level, text, clipId, clips)
            return true
        } catch (e: Exception) {
            android.util.Log.w(TAG_MUTE, "bus-parole-fantome level=$level texte=[$text] cause=${e.message}")
            if (holdToken == token) activeLevel = IDLE_LEVEL
            refundTurn(antiRepeatKey)
            return false
        }
    }

    /**
     * Parole tenue (niveau + jeton), lue par le porte-parole à l'entrée de
     * [VoiceBusSpeaker.say] — sous le verrou de [post], donc sans course :
     * c'est bien la parole qui vient d'être acceptée.
     */
    @Synchronized
    internal fun hold(): Pair<Int, Long> = activeLevel to holdToken

    /** Vrai si le bus tient encore la parole ([level], [token]). */
    @Synchronized
    internal fun holds(level: Int, token: Long): Boolean =
        activeLevel == level && holdToken == token

    /**
     * Rend le tour d'antirépétition d'une annonce jetée (SPEC v4 § 5 :
     * pas vraiment parlée = pas de tour consommé) ; `null` = sans clé,
     * rien à rendre. Privé : seul [post] jette.
     */
    private fun refundTurn(key: String?) {
        if (key == null) return
        if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-tour-rendu cle=$key")
        AntiRepeat.forget(key)
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
    fun onSpeechIdle() = onSpeechIdle(cut = false)

    /**
     * Fin de lecture, [cut] vrai quand la lecture a été **coupée** (parole du
     * user, sortie d'écran, intro, arrêt du lecteur de clips) et non terminée :
     * la phrase escaliers retenue est alors abandonnée (tour rendu) au lieu
     * d'être dite par-dessus la raison de la coupe.
     *
     * Le corps n'est **pas** sous le verrou du bus : la phrase retenue est
     * retirée et la place réservée sous verrou, puis parlée hors verrou —
     * `say` prend le verrou du lecteur et le rappel de fin de lecture le
     * prend en tenant celui du lecteur (jamais deux verrous croisés).
     */
    fun onSpeechIdle(cut: Boolean) {
        val next: PendingStairs?
        synchronized(this) {
            if (userHoldsMic || chatReads) return
            if (cut) {
                pendingStairs?.let { refundTurn(it.key) }
                pendingStairs = null
                next = null
            } else {
                next = takePendingStairs()
            }
            if (next != null) {
                activeLevel = OutdoorLevel.STAIRS
                lastSayMs = clock()
                lastStairsSayMs = lastSayMs
                holdToken++
            } else {
                if (activeLevel == IDLE_LEVEL) return
                activeLevel = IDLE_LEVEL
            }
        }
        if (next != null) {
            val target = speaker
            if (target != null) {
                sayOrGhost(target, OutdoorLevel.STAIRS, next.text, next.clipId, next.clips, next.key)
                return
            }
            refundTurn(next.key)
            return
        }
        onIdle?.invoke()
    }

    /**
     * Retire la phrase escaliers retenue **sous verrou** : rend son tour et
     * `null` si elle est périmée ([STAIRS_PENDING_TTL_MS] : un compte qui
     * date de la phrase précédente ne se dit plus — le prochain tick
     * repostera le compte courant) ; rend la phrase sinon.
     */
    private fun takePendingStairs(): PendingStairs? {
        val next = pendingStairs ?: return null
        pendingStairs = null
        val nowMs = clock()
        if (nowMs - next.atMs > STAIRS_PENDING_TTL_MS) {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-escaliers-perime texte=[${next.text}]")
            refundTurn(next.key)
            return null
        }
        if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-escaliers-enchaine texte=[${next.text}]")
        return next
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
            // Le user prend la parole : la phrase escaliers en attente est
            // abandonnée (le compte sera reposté au tick suivant), son tour rendu.
            pendingStairs?.let { refundTurn(it.key) }
            pendingStairs = null
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
        holdToken = 0L
        pendingStairs = null
        clock = { System.currentTimeMillis() }
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

/**
 * Fraîcheur d'une phrase escaliers retenue : 1,5 s. Au-delà, le compte a été
 * dit pour l'image d'avant (la phrase en cours a duré plus longtemps qu'un
 * cycle) — le redire serait périmé ; le tick suivant reposte le compte courant.
 */
internal const val STAIRS_PENDING_TTL_MS = 1_500L
