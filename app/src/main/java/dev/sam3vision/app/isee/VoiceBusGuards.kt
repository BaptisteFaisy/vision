package dev.sam3vision.app.isee

import dev.sam3vision.core.stairs.StairCountVoice

/**
 * Gardes du bus vocal ([VoiceBus], sorties pour le plafond 500 lignes) :
 * niveau incoupable orphelin (escaliers 6, trottoir 10 — sans elles, un
 * niveau orphelin tairait toute la voix) et durées des marches (fin des
 * marches, fraîcheur de la phrase retenue). Pures, testées en JVM.
 */

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
 * Fraîcheur d'une phrase escaliers retenue : un cycle de compte
 * ([StairCountVoice.REPEAT_MS]) — au-delà, le compte a été dit pour l'image
 * d'avant ; le redire serait périmé (ordre user du 03/11 : pas d'ancienne donnée).
 */
internal const val STAIRS_PENDING_TTL_MS = StairCountVoice.REPEAT_MS

/**
 * Fraîcheur d'un vœu retenu non-escaliers : 4 s, comme les gardes
 * d'orphelin ([STAIRS_BUS_TTL_MS]) — au-delà, la scène a changé (derrière
 * une longue réponse du chat, on ne dit pas une annonce d'il y a une
 * minute : périmée, clé rendue, le producteur re-poste s'il y a lieu).
 */
internal const val PENDING_TTL_MS = 4_000L

/**
 * Bus bloqué sur un trottoir : niveau 10 en cours depuis plus de
 * [SIDEWALK_BUS_TTL_MS] sans parole acceptée — miroir de [stairsBusStuck]
 * (un trottoir absolu orphelin tairait toute la voix). Pur, testé en JVM.
 */
internal fun sidewalkBusStuck(activeLevel: Int, nowMs: Long, lastSayMs: Long): Boolean =
    activeLevel == OutdoorLevel.SIDEWALK && nowMs - lastSayMs > SIDEWALK_BUS_TTL_MS

/** Silence maximal sous un niveau 10 (4 s : un clip trottoir dure moins d'1 s). */
internal const val SIDEWALK_BUS_TTL_MS = 4_000L

/**
 * Incoupable qui barre [level] : le trottoir en cours barre tout sauf un
 * trottoir (§ 45), les escaliers en cours barrent tout sauf des escaliers
 * (retenus, § 44) et un trottoir (qui coupe, § 45) — `null` = rien ne
 * barre. Rend le motif du journal (`trottoir` / `escaliers`). Pur.
 */
internal fun uncuttableBlocker(activeLevel: Int, level: Int): String? {
    if (activeLevel == OutdoorLevel.SIDEWALK && level != OutdoorLevel.SIDEWALK) return "trottoir"
    if (activeLevel == OutdoorLevel.STAIRS && level != OutdoorLevel.STAIRS && level != OutdoorLevel.SIDEWALK) return "escaliers"
    return null
}

/**
 * Observateurs du bus vocal, sortis de [VoiceBus] pour le plafond 500
 * lignes : extensions sur l'objet, donc appels inchangés
 * (`VoiceBus.stairsSpeaking()`, y compris non qualifiés dans le bus).
 * Chacun prend le verrou du bus ([synchronized], même moniteur que les
 * `@Synchronized` membres) et lit l'état interne en lecture seule — les
 * écritures restent dans [VoiceBus].
 */

/**
 * Parole tenue (niveau + jeton), lue par le porte-parole à l'entrée de
 * `say` — sous le verrou de `post`, donc sans course : c'est bien la
 * parole qui vient d'être acceptée.
 */
internal fun VoiceBus.hold(): Pair<Int, Long> = synchronized(this) { activeLevel to holdToken }

/** Vrai si le bus tient encore la parole ([level], [token]). */
internal fun VoiceBus.holds(level: Int, token: Long): Boolean =
    synchronized(this) { activeLevel == level && holdToken == token }

/**
 * Vrai quand des escaliers parlent (niveau 6 en cours) : les producteurs
 * s'y taisent en amont (sans consommer leur tour d'antirépétition —
 * offre et guetteurs directs) au lieu d'être jetés par `post`.
 */
fun VoiceBus.stairsSpeaking(): Boolean = synchronized(this) { activeLevel == OutdoorLevel.STAIRS }

/** Vrai quand une bordure parle (niveau 5) : la boucle « curb up » s'y tait en amont au lieu de se couper elle-même. */
fun VoiceBus.curbSpeaking(): Boolean = synchronized(this) { activeLevel == OutdoorLevel.CURB }

/** Vrai quand un trottoir parle (niveau 10 absolu, § 45) : producteurs et GPS attendent sa fin. */
fun VoiceBus.sidewalkSpeaking(): Boolean = synchronized(this) { activeLevel == OutdoorLevel.SIDEWALK }

/**
 * Vrai quand un mot de guidage parle (manœuvre 11, rappel 3 — ≤0,5 s) :
 * les producteurs frame s'y taisent en amont (même motif que
 * [stairsSpeaking]) au lieu d'être jetés par `post`.
 */
fun VoiceBus.guidanceSpeaking(): Boolean =
    synchronized(this) { activeLevel == OutdoorLevel.GPS_EVENT || activeLevel == OutdoorLevel.GPS_REMINDER }

/**
 * Vrai quand les escaliers sont actifs à [nowMs] : compte en cours, ou
 * dernier compte il y a moins de [STAIRS_QUIET_MS] (le chat attend la
 * fin des marches, pas le prochain silence — une tranche ne tient pas
 * dans l'intervalle).
 */
fun VoiceBus.stairsActive(nowMs: Long): Boolean =
    synchronized(this) { stairsSpeaking() || nowMs - lastStairsSayMs < STAIRS_QUIET_MS }

/**
 * Silence écoulé depuis la dernière parole acceptée (repli 12 : parler
 * seulement après 300 ms sans rien).
 */
fun VoiceBus.silenceMs(nowMs: Long): Long = synchronized(this) { (nowMs - lastSayMs).coerceAtLeast(0L) }
