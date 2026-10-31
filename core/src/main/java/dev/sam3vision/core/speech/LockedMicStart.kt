package dev.sam3vision.core.speech

/**
 * Les trois intentions du micro verrouillé, définies ici une seule fois : le pont Android
 * ([LockedMicBridge], `:app`) ne fait que les reprendre, et le service les interprète par
 * [parseMicStartAction]. Mêmes chaînes qu'avant, un seul endroit.
 */
object MicStartActions {
    /** Armer : service de premier plan, micro fermé, notification prête. */
    const val ARM = "dev.sam3vision.app.locked.ARM"

    /** Un seul geste : fermé → écoute ; en écoute → envoie. */
    const val TOGGLE = "dev.sam3vision.app.locked.TOGGLE"

    /** Tout arrêter (notification « Arrêter », app fermée). */
    const val STOP = "dev.sam3vision.app.locked.STOP"
}

/** L'intention lue dans le démarrage, interprétée avant tout effet Android. */
enum class MicStartAction {
    ARM,
    TOGGLE,
    STOP,
}

/**
 * Lit l'action de l'intention : une intention nulle (relance système `START_STICKY`) ou
 * inconnue arme, comme avant — le service mort se remet debout au lieu de ne rien faire.
 */
fun parseMicStartAction(action: String?): MicStartAction = when (action) {
    MicStartActions.STOP -> MicStartAction.STOP
    MicStartActions.TOGGLE -> MicStartAction.TOGGLE
    else -> MicStartAction.ARM
}

/** Motifs de refus, affichés dans la tuile et la notification, prononcés par TalkBack. */
object MicStartRefus {
    /** Le geste a échoué : le micro verrouillé n'est pas disponible. */
    const val UNAVAILABLE = "locked mic unavailable"

    /** L'armement est refusé : il faut ouvrir iSee (premier plan interdit en arrière-plan). */
    const val OPEN_ISEE = "open iSee to arm the mic"

    /** Le geste a levé après l'armement : le service reste armé, l'échec est dit. */
    const val TOGGLE_FAILED = "mic unavailable (gesture refused)"
}

/**
 * Ce qu'un démarrage a décidé, avant tout code de retour Android : le service le traduit en
 * `START_STICKY` (armé) ou `START_NOT_STICKY` (refusé, arrêté), et journalise les avertissements.
 */
sealed interface MicStartOutcome {
    /** Un avertissement par effet gardé qui a levé : rien n'est avalé en silence. */
    val warnings: List<String>

    /** Le service est armé (premier plan pris) ; `gesture` dit si le geste a été joué. */
    data class Armed(
        val gesture: Boolean,
        override val warnings: List<String> = emptyList(),
    ) : MicStartOutcome

    /** L'armement est refusé : l'état parlé porte [reason], le service est arrêté. */
    data class Refused(
        val reason: String,
        override val warnings: List<String> = emptyList(),
    ) : MicStartOutcome

    /** L'arrêt demandé est fait : micro relâché, notification retirée, service arrêté. */
    data class Stopped(
        override val warnings: List<String> = emptyList(),
    ) : MicStartOutcome
}

/**
 * Tous les effets Android d'un démarrage, fournis par le service ; le runner ([LockedMicStartRunner])
 * les appelle un par un, chacun gardé : celui qui lève est noté et le suivant part quand même.
 * `enterForeground` lève au refus (dont la `SecurityException` du type `microphone` sur
 * Android 14+ quand l'app est en arrière-plan) : le runner la traduit en refus parlé, jamais en FATAL.
 */
interface LockedMicStartFx {
    /** Publie l'état (pont de la tuile, puis notification). */
    fun publish(state: LockedMicState)

    /** Prend le premier plan avec la notification de [state] ; lève au refus système. */
    fun enterForeground(state: LockedMicState)

    /** Quitte le premier plan et retire la notification. */
    fun stopForeground()

    /** Arrête le service. */
    fun stopSelf()

    /** Joue le geste unique : fermé → écoute ; en écoute → envoie. */
    fun toggle()

    /** Annule l'envoi automatique (chemin d'arrêt). */
    fun cancelAutoSend()

    /** Ferme le micro et le rend (chemin d'arrêt). */
    fun releaseRecorder()

    /** Relâche le réveil partiel (chemin d'arrêt). */
    fun releaseWakeLock()

    /** Coupe la voix (chemin d'arrêt). */
    fun stopVoice()
}

/**
 * Déroule un démarrage du micro verrouillé : **premier plan ou arrêt, sans jamais lever**.
 *
 * C'est ici, et nulle part ailleurs, que vit la garantie : chaque effet est gardé
 * ([guarded]), donc une notification qui lève (`publish` → `show`), un premier plan refusé
 * ou une voix qui coupe mal n'empêchent ni l'arrêt ([MicStartOutcome.Refused],
 * [MicStartOutcome.Stopped]) ni le retour à l'appelant. Le service ne fait que fournir les
 * effets et traduire le résultat en code de retour.
 *
 * Ordre voulu : le premier plan est pris **avant** toute publication, pour que la promesse
 * système soit tenue au plus tôt ; un ré-armement (`READY`, `FAILED`…) garde l'état affiché
 * et reprend juste le premier plan, comme avant.
 */
class LockedMicStartRunner(private val fx: LockedMicStartFx) {

    private val warnings = mutableListOf<String>()

    /**
     * Déroule le démarrage demandé par [action] depuis [current] : arrêt, refus parlé, ou
     * premier plan puis geste. Rend la décision ; ne lève jamais.
     */
    fun run(action: MicStartAction, current: LockedMicState, hasAudio: Boolean): MicStartOutcome {
        if (action == MicStartAction.STOP) return stop(current)
        val gesture = action == MicStartAction.TOGGLE
        if (!hasAudio) return refuse(current, if (gesture) MicStartRefus.UNAVAILABLE else MicStartRefus.OPEN_ISEE)
        val arming = current.phase == LockedMicPhase.OFF
        val shown = if (arming) current.armed() else current
        if (!guarded("premier plan") { fx.enterForeground(shown) }) {
            return refuse(current, if (gesture) MicStartRefus.UNAVAILABLE else MicStartRefus.OPEN_ISEE)
        }
        if (arming) guarded("publication d'armement") { fx.publish(shown) }
        if (gesture && !guarded("geste") { fx.toggle() }) {
            guarded("publication d'échec") { fx.publish(shown.failed(MicStartRefus.TOGGLE_FAILED)) }
        }
        return MicStartOutcome.Armed(gesture, warnings.toList())
    }

    /** Arrêt demandé : démontage complet, état remis à zéro, notification retirée. */
    private fun stop(current: LockedMicState): MicStartOutcome {
        guarded("annulation de l'envoi") { fx.cancelAutoSend() }
        guarded("libération du micro") { fx.releaseRecorder() }
        guarded("libération du réveil") { fx.releaseWakeLock() }
        guarded("arrêt de la voix") { fx.stopVoice() }
        guarded("publication d'arrêt") { fx.publish(current.off()) }
        guarded("sortie du premier plan") { fx.stopForeground() }
        guarded("arrêt du service") { fx.stopSelf() }
        return MicStartOutcome.Stopped(warnings.toList())
    }

    /** Dégradation honnête : l'état dit pourquoi, la notification tombe, le service s'arrête. */
    private fun refuse(current: LockedMicState, reason: String): MicStartOutcome {
        guarded("publication de refus") { fx.publish(current.failed(reason)) }
        guarded("sortie du premier plan") { fx.stopForeground() }
        guarded("arrêt du service") { fx.stopSelf() }
        return MicStartOutcome.Refused(reason, warnings.toList())
    }

    /**
     * Exécute [block] et rend `false` s'il a levé (avertissement noté, suite garantie).
     * Attrape [Throwable] exprès : un arrêt manqué tuerait le processus dix secondes plus tard
     * (`ForegroundServiceDidNotStartInTime`), ce qui est pire que tout.
     */
    private fun guarded(label: String, block: () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            warnings += "$label : ${t.javaClass.simpleName} — ${t.message}"
            false
        }
    }
}
