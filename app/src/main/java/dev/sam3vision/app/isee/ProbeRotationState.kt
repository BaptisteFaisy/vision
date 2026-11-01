package dev.sam3vision.app.isee

import dev.sam3vision.core.live.ConceptAccumulator
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.PromptRotation
import dev.sam3vision.core.model.LiveFrame
import java.util.concurrent.atomic.AtomicLong

/**
 * Rotation des concepts de la sonde, hors [LiveProbe] (plafond 500 lignes) :
 * 3 groupes ([PromptRotation.split], `person` partout), un groupe par frame,
 * réponses fusionnées dans [accumulator]. Voix, overlay et Traversée lisent la
 * fusion : mêmes événements qu'en batch complet, post serveur ~39 ms au lieu
 * de ~67 ms (bench A100 batch-7 vs batch-21).
 *
 * Levier de repli `ROTATION_OFF` : [enabled] à `false` — la sonde renvoie la
 * liste complète à chaque frame, sans accumulation. Actif par défaut :
 * l'accumulation est prouvée sûre par les tests (continuité, négatif frais,
 * expiry, voix inchangée sur scénario suivi).
 */
internal class ProbeRotationState {

    /**
     * Rotation en service (`false` = repli `ROTATION_OFF` : liste complète par
     * frame, sans accumulation). Bascule à chaud : la frame en vol garde ses
     * concepts (capturés à l'envoi), la suivante suit le nouveau régime.
     */
    var enabled: Boolean = true

    /** Groupes du cycle, rebâtis à chaque (re)configuration par [usePrompts]. */
    private var groups: List<List<String>> = listOf(LivePrompts.default)

    /** Compteur de frames envoyées : atomique, deux vols peuvent se chevaucher. */
    private val seq = AtomicLong(0)

    /** Mémoire `concept → derniers objets` : nourrit voix et overlay en continu. */
    val accumulator = ConceptAccumulator()

    /** Envoi d'une frame : ses concepts, et si la réponse devra être accumulée. */
    data class Send(val prompts: List<String>, val rotated: Boolean)

    /**
     * Nouvelle liste de concepts : groupes rebâtis, mémoire et compteur remis
     * à zéro — un concept sorti ne hante plus la voix dès la reconfiguration.
     */
    fun usePrompts(prompts: List<String>) {
        groups = PromptRotation.split(LivePrompts.normalize(prompts))
        reset()
    }

    /** Nouvelle séance : mémoire et compteur oubliés, groupes gardés. */
    fun reset() {
        accumulator.reset()
        seq.set(0)
    }

    /**
     * Concepts de la prochaine frame : le groupe tournant ([enabled]), la liste
     * complète sinon — ou quand le groupe est vide (liste dégénérée d'un seul
     * concept hors `person`) : jamais un groupe vide au serveur.
     */
    fun send(full: List<String>): Send {
        if (!enabled) return Send(full, rotated = false)
        val group = PromptRotation.groupFor(groups, seq.getAndIncrement())
        if (group.isEmpty()) return Send(full, rotated = false)
        return Send(group, rotated = true)
    }

    /** Réponse d'une frame tournante : objets fusionnés (voir [ConceptAccumulator]). */
    fun accumulate(sent: List<String>, frame: LiveFrame, nowMs: Long): LiveFrame =
        frame.copy(objects = accumulator.update(sent, frame.objects, nowMs))
}

/**
 * Frame à publier : la fusion accumulée quand la frame tournait ([Send.rotated],
 * concepts capturés à l'envoi — un vol chevauché ne mélange pas les groupes),
 * la réponse brute sinon. `null` = échec, inchangé.
 */
internal fun LiveProbe.accumulated(raw: LiveFrame?, prompts: List<String>, rotated: Boolean): LiveFrame? {
    if (raw == null || !rotated) return raw
    return rotation.accumulate(prompts, raw, System.currentTimeMillis())
}
