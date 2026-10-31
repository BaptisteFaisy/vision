package dev.sam3vision.core.crossing

import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.share.normAngleDeg
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.abs

/**
 * Côté du constat : le scan parle de chaque côté séparément.
 */
enum class ScanSide { Left, Right }

/**
 * Phrases du scan traversée : ce que dit iSee à une personne aveugle qui
 * vient de poser le pied sur le passage. Le « Stop. » est le mot du guidage
 * ([GuidanceWord.Stop], clip < 100 ms, seul urgent — contrat
 * `docs/VOIX-DANGER.md` § 11) ; consignes et constats sont doux (synthèse
 * réseau, attendent le silence). Une voiture « à gauche » couvre tous les
 * véhicules moteurs du veto en mouvement (voiture, bus, camion, moto), le
 * mot de l'utilisateur restant « voiture ».
 *
 * Pur et testé en JVM.
 */
object CrosswalkScanText {

    /** Consigne du côté gauche (« Regardez à gauche. »). */
    fun left(language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> "Regardez à gauche."
        SpeechLanguage.En -> "Look left."
    }

    /** Consigne du côté droit (« Regardez à droite. »). */
    fun right(language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> "Regardez à droite."
        SpeechLanguage.En -> "Look right."
    }

    /** Constat libre du côté : aucun véhicule en mouvement vu. */
    fun nothing(side: ScanSide, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.Fr -> when (side) {
                ScanSide.Left -> "Rien à gauche."
                ScanSide.Right -> "Rien à droite."
            }
            SpeechLanguage.En -> when (side) {
                ScanSide.Left -> "Nothing on the left."
                ScanSide.Right -> "Nothing on the right."
            }
        }

    /** Constat danger du côté : un véhicule en mouvement vu. */
    fun danger(side: ScanSide, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.Fr -> when (side) {
                ScanSide.Left -> "Attention, voiture à gauche."
                ScanSide.Right -> "Attention, voiture à droite."
            }
            SpeechLanguage.En -> when (side) {
                ScanSide.Left -> "Caution, car on the left."
                ScanSide.Right -> "Caution, car on the right."
            }
        }
}

/**
 * Phase du scan : arrêt, côté gauche (consigne puis constat), côté droit
 * (consigne puis constat), puis re-vérification ou silence. `Done` tient
 * tant que le déclencheur tient ; la perte du déclencheur ré-arme (`Idle`).
 */
enum class CrosswalkScanPhase { Idle, Stop, Left, ReportLeft, Right, ReportRight, Rewait, Done }

/**
 * Scan « stop, regardez à gauche et à droite » : quand l'utilisateur est
 * **sur** le passage piéton ([trigger] vrai — arrivé **et** passage à moins
 * de [SCAN_METERS], calculé par l'appelant), iSee dit « Stop. », fait
 * regarder à gauche puis à droite **par rapport à la route**, et dit à
 * haute voix, pour chaque côté, si une voiture en mouvement a été vue.
 *
 * Séquence : `Stop.` ([GuidanceWord.Stop], urgent, [STOP_MS]) → consigne
 * gauche → virage à gauche prouvé ([LOOK_DEG] de lacet vers la gauche
 * depuis le cap du scan, ou [WAIT_TIMEOUT_MS]) → observation ([CHECK_MS],
 * [CONFIRM_MOVING_FRAMES] frames en mouvement pour un danger) → constat
 * gauche parlé ([REPORT_MS]) → consigne droite → virage à droite prouvé →
 * observation → constat droit parlé → silence si les deux côtés sont
 * libres, sinon re-vérification obligatoire : attente silencieuse
 * ([REWAIT_MS], le temps que le véhicule passe) puis nouveau cycle
 * complet (consignes seules, sans « Stop. »), jusqu'à [MAX_SCAN_CYCLES]
 * cycles — au-delà, le scan rend la voix (le veto garde la traversée,
 * les voix douces reprennent).
 *
 * La référence, c'est la route : [refYaw0] est le cap à l'entrée du scan
 * (l'utilisateur face à la traversée), et chaque virage est
 * **directionnel** — tourner à droite quand on a demandé la gauche ne
 * valide pas la gauche (signe du delta exigé, ±[LOOK_DEG]). Un virage
 * insuffisant ou un capteur absent bascule sur le repli temporisé (le
 * constat vaut alors pour les fenêtres d'observation, limite documentée).
 *
 * Entrées par frame : [trigger] le déclencheur, [yawDeg] le cap du
 * téléphone (degrés, `null` = sans capteur), [movingNow] « un véhicule est
 * en mouvement dans cette vue » ([VehicleStateWatcher], déjà nourri par la
 * voix traversée), [feuSpeaking] vrai quand le feu parle. Le feu ne fait
 * pas avancer le scan : les horloges gèlent ([freezeClock]) et le mouvement
 * n'est pas compté (le guetteur véhicules n'est pas nourri pendant le
 * quiet) — le « Stop », les consignes et les constats se disent entiers
 * après, jamais couverts ni tronqués.
 *
 * Le constat parle de ce qui a été **vu** : le mouvement latéral (voiture
 * qui traverse le champ à distance constante, le danger n° 1 d'un
 * piéton) est détecté par le guetteur, et l'observation ne compte que le
 * regard stabilisé (vitesse de lacet sous [SETTLE_DEG_PER_SEC] — un
 * téléphone en rotation fait glisser les boîtes garées, qui ressembleraient
 * à du mouvement).
 *
 * Pur Kotlin testé en JVM : aucune horloge implicite ([atMs] monotone),
 * aucun capteur — le lacet est injecté.
 */
class CrosswalkScan {

    /** Phase courante (celle de la dernière frame traitée). */
    var phase: CrosswalkScanPhase = CrosswalkScanPhase.Idle
        private set

    private var phaseAtMs = 0L
    private var checkAtMs = NO_CHECK
    private var lastFrameAtMs = 0L
    private var lastYaw: Float? = null
    private var lastAtMs = 0L
    private var prevYaw: Float? = null
    private var prevAtMs = 0L
    private var triggerHits = 0
    private var movingFrames = 0
    private var dangerLeft = false
    private var dangerRight = false
    private var cyclesDone = 0

    /**
     * Cap à l'entrée du scan (face à la route) : les deux virages s'y
     * mesurent en signé (gauche négatif, droite positif).
     */
    private var refYaw0: Float? = null

    /**
     * Phrase du scan à [atMs] (`null` = silence — le feu parle, le scan est
     * fini, ou pas de scan). Émise à chaque frame du pas courant : la sonde
     * déduplique (`distinctUntilChanged`), un pas ne se répète jamais.
     */
    fun update(
        atMs: Long,
        trigger: Boolean,
        yawDeg: Float?,
        movingNow: Boolean,
        feuSpeaking: Boolean,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        trackYaw(yawDeg, atMs)
        freezeClock(atMs, feuSpeaking)
        if (!trigger) {
            reset()
            return null
        }
        if (phase == CrosswalkScanPhase.Done) return null
        if (phase == CrosswalkScanPhase.Idle) {
            triggerHits += 1
            if (triggerHits < TRIGGER_CONFIRM_FRAMES) return null
            enter(CrosswalkScanPhase.Stop, atMs)
            refYaw0 = lastYaw
        }
        val phrase = when (phase) {
            CrosswalkScanPhase.Stop -> onStop(atMs, language)
            CrosswalkScanPhase.Left -> onSide(atMs, movingNow, feuSpeaking, left = true, language)
            CrosswalkScanPhase.ReportLeft -> onReport(atMs, left = true, language)
            CrosswalkScanPhase.Right -> onSide(atMs, movingNow, feuSpeaking, left = false, language)
            CrosswalkScanPhase.ReportRight -> onReport(atMs, left = false, language)
            CrosswalkScanPhase.Rewait -> onRewait(atMs, language)
            CrosswalkScanPhase.Idle, CrosswalkScanPhase.Done -> null
        }
        return if (feuSpeaking) null else phrase
    }

    /** Oublie phase, virages et constats (mode coupé, nouvelle séance). */
    fun reset() {
        phase = CrosswalkScanPhase.Idle
        phaseAtMs = 0L
        checkAtMs = NO_CHECK
        lastFrameAtMs = 0L
        refYaw0 = null
        lastYaw = null
        lastAtMs = 0L
        prevYaw = null
        prevAtMs = 0L
        triggerHits = 0
        movingFrames = 0
        dangerLeft = false
        dangerRight = false
        cyclesDone = 0
    }

    /**
     * Horloges gelées pendant le feu : le temps parlé par le feu n'avance
     * ni les timeouts ni les constats — tout se dit entier après.
     */
    private fun freezeClock(atMs: Long, feuSpeaking: Boolean) {
        val gap = (atMs - lastFrameAtMs).coerceAtLeast(0L)
        lastFrameAtMs = atMs
        if (!feuSpeaking || gap == 0L) return
        phaseAtMs += gap
        if (checkAtMs != NO_CHECK) checkAtMs += gap
    }

    /** « Stop. » puis, passé [STOP_MS], la consigne gauche. */
    private fun onStop(atMs: Long, language: SpeechLanguage): String {
        if (atMs - phaseAtMs >= STOP_MS) {
            enter(CrosswalkScanPhase.Left, atMs)
            return CrosswalkScanText.left(language)
        }
        return GuidanceWord.Stop.text(language)
    }

    /** Consigne du côté : virage prouvé puis observation, constat à la fin. */
    private fun onSide(
        atMs: Long,
        movingNow: Boolean,
        feuSpeaking: Boolean,
        left: Boolean,
        language: SpeechLanguage,
    ): String {
        val yaw = lastYaw
        if (yaw != null && refYaw0 == null) refYaw0 = yaw
        if (checkAtMs == NO_CHECK) {
            val turned = if (left) turnedLeft(refYaw0, yaw) else turnedRight(refYaw0, yaw)
            if (turned || atMs - phaseAtMs >= WAIT_TIMEOUT_MS) checkAtMs = atMs
        } else {
            observe(movingNow, feuSpeaking)
            if (atMs - checkAtMs >= CHECK_MS) return finishSide(atMs, left, language)
        }
        return if (left) CrosswalkScanText.left(language) else CrosswalkScanText.right(language)
    }

    /** Fin du côté : le danger est latché (écrasé, pas cumulé), le constat parle aussitôt. */
    private fun finishSide(atMs: Long, left: Boolean, language: SpeechLanguage): String {
        val danger = movingFrames >= CONFIRM_MOVING_FRAMES
        if (left) dangerLeft = danger else dangerRight = danger
        enter(if (left) CrosswalkScanPhase.ReportLeft else CrosswalkScanPhase.ReportRight, atMs)
        return reportPhrase(left, language)
    }

    /** Constat tenu [REPORT_MS], puis consigne droite ou fin de cycle. */
    private fun onReport(atMs: Long, left: Boolean, language: SpeechLanguage): String? {
        if (atMs - phaseAtMs < REPORT_MS) return reportPhrase(left, language)
        if (left) {
            enter(CrosswalkScanPhase.Right, atMs)
            return CrosswalkScanText.right(language)
        }
        return finishCycle(atMs)
    }

    /**
     * Fin de cycle : libres des deux côtés ou plafond atteint ([MAX_SCAN_CYCLES]),
     * le scan rend la voix ; sinon attente silencieuse puis nouveau cycle.
     */
    private fun finishCycle(atMs: Long): String? {
        cyclesDone += 1
        if (!dangerLeft && !dangerRight || cyclesDone >= MAX_SCAN_CYCLES) {
            enter(CrosswalkScanPhase.Done, atMs)
            return null
        }
        enter(CrosswalkScanPhase.Rewait, atMs)
        return null
    }

    /** Attente silencieuse : le véhicule passe, puis nouveau cycle (consignes seules). */
    private fun onRewait(atMs: Long, language: SpeechLanguage): String? {
        if (atMs - phaseAtMs < REWAIT_MS) return null
        enter(CrosswalkScanPhase.Left, atMs)
        return CrosswalkScanText.left(language)
    }

    /** Constat du côté : danger latché ou rien vu, dit à haute voix. */
    private fun reportPhrase(left: Boolean, language: SpeechLanguage): String {
        val side = if (left) ScanSide.Left else ScanSide.Right
        val danger = if (left) dangerLeft else dangerRight
        return if (danger) CrosswalkScanText.danger(side, language)
        else CrosswalkScanText.nothing(side, language)
    }

    /** Observation : le mouvement ne compte que regard stabilisé, hors feu. */
    private fun observe(movingNow: Boolean, feuSpeaking: Boolean) {
        if (feuSpeaking || !gazeStable()) return
        if (movingNow) movingFrames += 1
    }

    /** Entrée de phase : horloge et observation ré-armées. */
    private fun enter(phase: CrosswalkScanPhase, atMs: Long) {
        this.phase = phase
        phaseAtMs = atMs
        checkAtMs = NO_CHECK
        movingFrames = 0
    }

    /**
     * Virage à gauche prouvé : [LOOK_DEG] ou plus vers la gauche depuis le
     * cap du scan (signe exigé : tourner à droite ne valide pas la gauche).
     * L'azimut Android croît vers l'est (face à l'est = +90°) : regarder à
     * gauche, c'est décroître.
     */
    private fun turnedLeft(refYaw: Float?, yaw: Float?): Boolean {
        if (refYaw == null || yaw == null) return false
        return normAngleDeg(yaw - refYaw) <= -LOOK_DEG
    }

    /** Virage à droite prouvé : miroir de [turnedLeft]. */
    private fun turnedRight(refYaw: Float?, yaw: Float?): Boolean {
        if (refYaw == null || yaw == null) return false
        return normAngleDeg(yaw - refYaw) >= LOOK_DEG
    }

    /** Regard stabilisé : lacet lent (ou inconnu — repli temporisé). */
    private fun gazeStable(): Boolean {
        val last = lastYaw ?: return true
        val prev = prevYaw ?: return true
        val dtSeconds = (lastAtMs - prevAtMs) / 1000f
        if (dtSeconds <= 0f) return true
        return abs(normAngleDeg(last - prev)) / dtSeconds <= SETTLE_DEG_PER_SEC
    }

    /** Mémorise le lacet (rupture de chaîne sur `null` : pas de vitesse devinée). */
    private fun trackYaw(yawDeg: Float?, atMs: Long) {
        val yaw = yawDeg?.takeIf { it.isFinite() }
        if (yaw == null) {
            lastYaw = null
            prevYaw = null
            return
        }
        prevYaw = lastYaw
        prevAtMs = lastAtMs
        lastYaw = yaw
        lastAtMs = atMs
    }

    companion object {
        /** Passage à moins de cette distance (m) : le scan peut s'armer (ordre user). */
        const val SCAN_METERS = 2f

        /** Frames de déclencheur de suite avant le « Stop. » (un positif isolé = bruit). */
        const val TRIGGER_CONFIRM_FRAMES = 2

        /** Durée du « Stop. » (ms) avant la consigne gauche (clip < 100 ms + marge). */
        const val STOP_MS = 800L

        /**
         * Virage prouvé : écart de lacet (degrés, signé) depuis le cap du
         * scan — 60° braque franchement le champ (~60° d'ouverture) vers le
         * côté de la route (prior à valider rue : 90° idéal, 60° tenable).
         */
        const val LOOK_DEG = 60f

        /** Sans virage prouvé, l'observation part quand même (repli temporisé). */
        const val WAIT_TIMEOUT_MS = 6_000L

        /** Fenêtre d'observation du côté (ms) : le constat parle de ce qui y est vu. */
        const val CHECK_MS = 2_500L

        /** Frames en mouvement dans la fenêtre pour un danger (un positif isolé = bruit). */
        const val CONFIRM_MOVING_FRAMES = 2

        /** Tenue du constat (ms) : le temps de le dire entier, même après le feu. */
        const val REPORT_MS = 2_000L

        /**
         * Attente silencieuse entre deux cycles (ms) : le temps que le
         * véhicule constaté passe avant de re-vérifier (prior à valider
         * rue : une voiture à 50 km/h libère le passage en quelques
         * secondes, 10 s laissent la marge).
         */
        const val REWAIT_MS = 10_000L

        /**
         * Cycles de scan au plus par traversée (1 initial + re-vérifications) :
         * au-delà, le scan rend la voix au lieu de rabâcher — le veto garde
         * la traversée et les voix douces reprennent (prior).
         */
        const val MAX_SCAN_CYCLES = 3

        /** Vitesse de lacet (degrés/s) sous laquelle le regard compte comme stabilisé. */
        const val SETTLE_DEG_PER_SEC = 20f

        /** Pas d'observation en cours (attente du virage). */
        private const val NO_CHECK = -1L
    }
}
