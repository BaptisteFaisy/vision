package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.live.AntiRepeat

/** Étiquette des diagnostics du mutisme spontané (`logcat -s VoiceMute:*`). */
private const val TAG_MUTE = "VoiceMute"

/**
 * Bus vocal, contrat partagé : `fun VoiceBus.post(level, text, clipId?, clips)`.
 *
 * Rien ne coupe (ordre user du 03/11/2026) : au repos, tout niveau parle ;
 * pendant une parole, l'accepté attend la fin du mot en place unique
 * ([VoicePending] : le plus petit niveau gagne, à égalité le dernier — la
 * position flèche du moment, ou le plus prioritaire) et se dit au repos ;
 * le refusé n'est jamais redit (les producteurs re-postent à chaque frame).
 * Échelle dehors 0 à 12 ([OutdoorLevel]), Traversée 100 à
 * 102 ([dev.sam3vision.core.crossing.CrossingLevels]). Le doublon exact du
 * mot en cours ne se remet pas en file (dit une fois, pas deux).
 *
 * La parole du user (niveau 1, [userSpeaking]) coupe encore : micro tenu,
 * elle abandonne l'attente, arrête la lecture et tait tout sauf le niveau 0
 * (qui parle par-dessus sans rien couper — le lecteur est déjà arrêté) ; en
 * fin de micro, l'attente éventuelle se dit. La lecture du chat
 * ([chatSpeaking]) tient le niveau 9 ; en fin de réponse, l'attente
 * éventuelle se dit avant la reprise. L'antirépétition vit en amont
 * ([dev.sam3vision.core.live.AntiRepeat], côté producteurs) : le bus
 * n'arbitre que l'instant, et rend le tour de tout jeté.
 *
 * Les absolus restent absolus, sans couper : escaliers (niveau 7, §§ 42/44)
 * et trottoir (niveau 10, § 45) prennent la place d'attente devant tous
 * (le trottoir devant les escaliers) et vont au bout, personne ne les en
 * déloge ; la bordure (niveau 6) passe devant un mot de guidage, pas
 * l'inverse. Périmée, la place se rend au lieu de se dire (compte : un
 * cycle ; autres : [PENDING_TTL_MS]).
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

    /**
     * Niveau de la parole en cours ([IDLE_LEVEL] = aucune). Interne : les
     * observateurs ([VoiceBusGuards.kt]) le lisent ; écrit dans [VoiceBus].
     */
    internal var activeLevel: Int = IDLE_LEVEL

    /**
     * Jeton de la parole en cours (+1 par parole acceptée) : le superviseur
     * de restitution ([BusSpeaker]) ne rend le bus que si le niveau ET le
     * jeton sont encore les siens — une parole plus récente ne se fait
     * jamais voler son tour.
     */
    internal var holdToken: Long = 0L

    /** Horodatage de la dernière parole acceptée (repli silence). */
    internal var lastSayMs: Long = 0L

    /** Horodatage du dernier compte accepté (le chat attend la fin, pas le silence). */
    internal var lastStairsSayMs: Long = 0L

    /** Place unique d'attente : parle à la fin du mot en cours, jamais par-dessus. */
    private val pending = VoicePending()

    /** Texte de la parole en cours (`null` = repos, micro tenu, ou lecture chat). */
    private var activeText: String? = null

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
     * [clips] si non vide) : parlé aussitôt si rien ne parle, retenu en
     * place unique ([VoicePending]) si [level] passe le rang en cours, jeté
     * sinon (jamais redit). La décision lit [activeLevel], pas le lecteur
     * (sinon une parole décidée mais pas encore jouée laisserait passer un
     * niveau plus bas).
     * [antiRepeatKey] rend son tour quand l'annonce est jetée (SPEC v4 § 5 :
     * une annonce avalée ne consomme pas son tour — le producteur l'avait
     * consommé par son pré-contrôle) ; `null` = sans clé (comportement
     * inchangé : GPS, chat, repli).
     *
     * Rend **vrai quand la phrase est retenue et émise** (tout de suite ou
     * à la fin du mot en cours), faux quand elle a été jetée (vide,
     * sans porte-parole, micro user, doublon, niveau écrasé) : depuis le
     * 02/11/2026 l'appelant sait donc s'il a parlé, au lieu de le supposer
     * (A9 § 3 D8 : le verdict traversée croyait avoir parlé alors que le bus
     * l'avait jeté ⇒ jusqu'à 2 s de silence, « Don't cross. » compris).
     * Aucun appelant existant ne casse : ignorer une valeur de retour est
     * légal en Kotlin. Faux aussi si le porte-parole lève (parole fantôme :
     * bus rendu aussitôt, tour rendu — [sayOrGhost], jamais de crash
     * producteur).
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
        if (stairsBusStuck(activeLevel, nowMs, lastSayMs) || sidewalkBusStuck(activeLevel, nowMs, lastSayMs)) {
            // Orphelin (4 s sans parole réelle, lecteur pendu) : bus rendu,
            // attente abandonnée (clé rendue, le producteur re-poste) — le
            // vœu courant repart sur un bus libre.
            activeLevel = IDLE_LEVEL
            activeText = null
            refundTurn(pending.abandon())
        }
        if (isDuplicateOfActive(level, text)) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=doublon-en-cours level=$level texte=[$text]")
            refundTurn(antiRepeatKey)
            return false
        }
        return postAccepted(target, level, text, clipId, clips, antiRepeatKey, nowMs)
    }

    /**
     * Doublon exact du mot en cours (même niveau, même texte) : déjà en
     * train de se dire, pas remis en file — sinon chaque re-post redirait
     * le mot à la fin (niveau 0 re-posté à chaque frame, redites GPS).
     * Faux au repos. Appelée sous le verrou de [post].
     */
    private fun isDuplicateOfActive(level: Int, text: String): Boolean =
        activeLevel != IDLE_LEVEL && level == activeLevel && text == activeText

    /**
     * Vœu gardé par [post] : trottoir et escaliers (absolus : parlent au
     * repos, attendent sinon), barre incoupable, refus bordure sur rappel,
     * écrasement d'échelle (sous le rang en cours : jeté) — sinon parole
     * immédiate au repos (niveau 0 sous micro user compris : le lecteur est
     * arrêté, il ne coupe rien), place unique en cours de parole. Sous verrou.
     */
    private fun postAccepted(
        target: VoiceBusSpeaker,
        level: Int,
        text: String,
        clipId: String?,
        clips: List<String>,
        antiRepeatKey: String?,
        nowMs: Long,
    ): Boolean {
        if (level == OutdoorLevel.SIDEWALK) return postSidewalk(target, text, clipId, clips, antiRepeatKey, nowMs)
        val blocker = uncuttableBlocker(activeLevel, level)
        if (blocker != null) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=$blocker-en-cours level=$level texte=[$text]")
            refundTurn(antiRepeatKey)
            return false
        }
        if (level == OutdoorLevel.STAIRS) {
            return postStairs(target, text, clipId, clips, antiRepeatKey, nowMs)
        }
        if (curbGuidanceRefused(level, text, antiRepeatKey)) return false
        if (activeLevel != IDLE_LEVEL && level > activeLevel && !curbPreemptsGuidance(level)) {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=ecrase level=$level actif=$activeLevel chat=$chatReads texte=[$text]")
            refundTurn(antiRepeatKey)
            return false
        }
        if (activeLevel != IDLE_LEVEL && !(userHoldsMic && level == OutdoorLevel.CAR_MOVING)) {
            return retainWish(PendingWish(level, text, clipId, clips, nowMs, antiRepeatKey))
        }
        activeLevel = level
        activeText = text
        lastSayMs = nowMs
        holdToken++
        return sayOrGhost(target, level, text, clipId, clips, antiRepeatKey)
    }

    /**
     * Retient [wish] en place unique ([VoicePending]) : vrai rangé (clé
     * remplacée rendue : elle ne se dira jamais), faux refusé (barré ou
     * sous le rang — clé rendue). Sous le verrou de [post].
     */
    private fun retainWish(wish: PendingWish): Boolean = when (val outcome = pending.retain(wish)) {
        is RetainOutcome.Stored -> {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-en-attente level=${wish.level} texte=[${wish.text}]")
            true
        }
        is RetainOutcome.Replaced -> {
            refundTurn(outcome.refundKey)
            if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-en-attente level=${wish.level} remplace texte=[${wish.text}]")
            true
        }
        is RetainOutcome.Rejected -> {
            android.util.Log.w(TAG_MUTE, "bus-jet motif=attente-prise level=${wish.level} texte=[${wish.text}]")
            refundTurn(wish.key)
            false
        }
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
     * Bordure (6) sur un mot de guidage en cours (manœuvre 11, rappel 4) :
     * elle passe devant (même ordre — l'écrasement d'échelle ne s'applique
     * pas) : retenue, dite à la fin du mot, jamais par-dessus.
     */
    private fun curbPreemptsGuidance(level: Int): Boolean =
        level == OutdoorLevel.CURB && guidanceSpeaking()

    /**
     * Poste une phrase escaliers (niveau 7) : parle au repos, sinon attend
     * en place unique (le plus récent l'emporte — §§ 42/44 : un compte ne
     * se coupe jamais lui-même, et depuis le 03/11 ne coupe plus les autres
     * non plus : il parle à la fin du mot). Rend vrai dans les deux cas :
     * retenue = elle parlera (ou périmera, tour rendu).
     */
    private fun postStairs(
        target: VoiceBusSpeaker,
        text: String,
        clipId: String?,
        clips: List<String>,
        antiRepeatKey: String?,
        nowMs: Long,
    ): Boolean {
        if (activeLevel != IDLE_LEVEL) {
            // `lastStairsSayMs` seul : le chat attend la fin des marches, mais
            // `lastSayMs` reste celui de la dernière parole RÉELLE — sinon la
            // garde `stairsBusStuck` (4 s) ne pourrait plus jamais libérer un
            // niveau 7 dont la fin de lecture s'est perdue.
            lastStairsSayMs = nowMs
            return retainWish(PendingWish(OutdoorLevel.STAIRS, text, clipId, clips, nowMs, antiRepeatKey))
        }
        activeLevel = OutdoorLevel.STAIRS
        activeText = text
        lastSayMs = nowMs
        lastStairsSayMs = nowMs
        holdToken++
        return sayOrGhost(target, OutdoorLevel.STAIRS, text, clipId, clips, antiRepeatKey)
    }

    /**
     * Trottoir absolu (§ 45) : parle au repos, sinon attend en place unique
     * devant tous (il ne coupe plus, il passe premier à la fin du mot —
     * micro user filtré par [post]). Antirépétition 10 s + clips courts.
     */
    private fun postSidewalk(target: VoiceBusSpeaker, text: String, clipId: String?, clips: List<String>, antiRepeatKey: String?, nowMs: Long): Boolean {
        if (activeLevel != IDLE_LEVEL) {
            return retainWish(PendingWish(OutdoorLevel.SIDEWALK, text, clipId, clips, nowMs, antiRepeatKey))
        }
        activeLevel = OutdoorLevel.SIDEWALK
        activeText = text
        lastSayMs = nowMs
        holdToken++
        return sayOrGhost(target, OutdoorLevel.SIDEWALK, text, clipId, clips, antiRepeatKey)
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
            if (holdToken == token) {
                activeLevel = IDLE_LEVEL
                activeText = null
            }
            refundTurn(antiRepeatKey)
            return false
        }
    }

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
     * Fin de lecture (appelée par le lecteur) : rend la parole, sauf si le
     * user tient le micro ou si le chat lit (ils gardent leur niveau).
     */
    fun onSpeechIdle() = onSpeechIdle(cut = false)

    /**
     * Fin de lecture, [cut] vrai quand la lecture a été **coupée** (parole du
     * user, sortie d'écran, intro, arrêt du lecteur de clips) et non terminée :
     * l'attente est alors abandonnée (tour rendu) au lieu d'être dite
     * par-dessus la raison de la coupe. Sinon, l'attente fraîche se dit, la
     * périmée se rend, et le repos ne sonne que sans attente.
     *
     * Le corps n'est **pas** sous le verrou du bus : l'attente est prise et
     * la place réservée sous verrou, puis parlée hors verrou — `say` prend
     * le verrou du lecteur et le rappel de fin de lecture le prend en tenant
     * celui du lecteur (jamais deux verrous croisés).
     */
    fun onSpeechIdle(cut: Boolean) {
        val next: PendingWish?
        synchronized(this) {
            if (userHoldsMic || chatReads) return
            if (cut) {
                refundTurn(pending.abandon())
                next = null
            } else {
                next = takeFreshPending()
            }
            if (next == null) {
                activeText = null
                if (activeLevel == IDLE_LEVEL) return
                activeLevel = IDLE_LEVEL
            }
        }
        if (next != null) {
            speakChained(next)
            return
        }
        onIdle?.invoke()
    }

    /**
     * Prend l'attente sous verrou : vide ou périmée (tour rendu), `null`
     * (le repos sonne) ; fraîche, place réservée (niveau, texte, jeton) et
     * vœu à dire hors verrou. Appelée sous le verrou du bus.
     */
    private fun takeFreshPending(): PendingWish? = when (val taken = pending.take(clock())) {
        is PendingTake.Empty -> null
        is PendingTake.Stale -> {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-attente-perime")
            refundTurn(taken.key)
            null
        }
        is PendingTake.Fresh -> {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-enchaine level=${taken.wish.level} texte=[${taken.wish.text}]")
            activeLevel = taken.wish.level
            activeText = taken.wish.text
            lastSayMs = clock()
            if (taken.wish.level == OutdoorLevel.STAIRS) lastStairsSayMs = lastSayMs
            holdToken++
            taken.wish
        }
    }

    /**
     * Dit le vœu réservé hors verrou (sans porte-parole : tour rendu, le
     * niveau reste tenu jusqu'à la prochaine fin de lecture — en prod le
     * porte-parole est câblé au démarrage, ce chemin est celui des tests).
     */
    private fun speakChained(next: PendingWish) {
        val target = speaker
        if (target != null) {
            sayOrGhost(target, next.level, next.text, next.clipId, next.clips, next.key)
            return
        }
        refundTurn(next.key)
    }

    /**
     * Parole du user : en commençant, abandonne l'attente (tour rendu — le
     * compte sera reposté au tick suivant), coupe la lecture en cours (chat
     * compris) et tait tout sauf le niveau 0 ; en finissant, dit l'attente
     * éventuelle (un niveau 0), sinon rend la parole (sauf si un niveau 0 a
     * parlé entre-temps : il garde la main).
     */
    fun userSpeaking(speaking: Boolean) {
        val next: PendingWish?
        synchronized(this) {
            if (speaking == userHoldsMic) return
            userHoldsMic = speaking
            if (speaking) {
                refundTurn(pending.abandon())
                speaker?.cut()
                activeLevel = OutdoorLevel.USER_SPEECH
                activeText = null
                return
            }
            if (activeLevel != OutdoorLevel.USER_SPEECH) return
            next = takeFreshPending()
            if (next == null) {
                activeLevel = IDLE_LEVEL
                activeText = null
            }
        }
        if (next != null) {
            speakChained(next)
            return
        }
        onIdle?.invoke()
    }

    /**
     * Lecture du chat : tient le niveau 9 tant qu'une réponse se lit (le
     * chat reprend depuis la coupure au lieu de perdre, côté lecture) ;
     * en fin, dit l'attente éventuelle (la réponse reprend au repos
     * suivant), sinon rend la parole (sauf préemption entre-temps).
     */
    fun chatSpeaking(speaking: Boolean) {
        val next: PendingWish?
        synchronized(this) {
            if (speaking == chatReads) return
            if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-chat lit=$speaking actif=$activeLevel")
            chatReads = speaking
            if (speaking) {
                activeLevel = OutdoorLevel.CHAT
                activeText = null
                return
            }
            if (activeLevel != OutdoorLevel.CHAT) return
            next = takeFreshPending()
            if (next == null) {
                activeLevel = IDLE_LEVEL
                activeText = null
            }
        }
        if (next != null) {
            speakChained(next)
            return
        }
        onIdle?.invoke()
    }

    /** Oublie la parole en cours (nouvelle séance, tests) : le câblage reste. */
    @Synchronized
    fun reset() {
        activeLevel = IDLE_LEVEL
        activeText = null
        userHoldsMic = false
        chatReads = false
        lastSayMs = 0L
        lastStairsSayMs = 0L
        holdToken = 0L
        pending.abandon()
        clock = { System.currentTimeMillis() }
    }
}
