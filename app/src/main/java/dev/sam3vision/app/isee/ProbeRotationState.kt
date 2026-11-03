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
 * de ~67 ms (bench A100 batch-7 vs batch-20).
 *
 * Repli `ROTATION_OFF` **par défaut** ([enabled] à `false`) : la sonde
 * renvoie la liste complète à chaque frame, sans accumulation. La rotation
 * n'interrogeait escaliers et trottoirs qu'une frame sur trois (même groupe)
 * et ils n'étaient plus reconnus. Réversible : [enabled] à `true` pour
 * retrouver un groupe par frame (prouvé par les tests : continuité, négatif
 * confirmé, expiry). Hors rotation, le filet [holdLastGood] rejoue une
 * réponse vide isolée au lieu d'effacer voix et overlay.
 */
internal class ProbeRotationState {

    /**
     * Rotation en service (`true` = un groupe par frame + accumulation ;
     * `false` = repli `ROTATION_OFF`, liste complète par frame). Bascule à
     * chaud : la frame en vol garde ses concepts (capturés à l'envoi), la
     * suivante suit le nouveau régime.
     */
    var enabled: Boolean = false

    /** Groupes du cycle, rebâtis à chaque (re)configuration par [usePrompts]. */
    private var groups: List<List<String>> = listOf(LivePrompts.default)

    /** Compteur de frames envoyées : atomique, deux vols peuvent se chevaucher. */
    private val seq = AtomicLong(0)

    /** Mémoire `concept → derniers objets` : nourrit voix et overlay en continu. */
    val accumulator = ConceptAccumulator()

    /** Dernière frame non vide (filet hors rotation) ; `null` = rien à garder. */
    private var lastGood: LiveFrame? = null

    /** Vides consécutifs depuis [lastGood] : 1 = rejoué, 2 = passé (vraiment vide). */
    private var misses = 0

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

    /** Nouvelle séance : mémoires et compteur oubliés, groupes gardés. */
    fun reset() {
        accumulator.reset()
        lastGood = null
        misses = 0
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

    /**
     * Filet 1-manqué hors rotation : une réponse vide isolée (troncature
     * d'une frame) rejoue la précédente au lieu d'effacer voix et overlay ;
     * deux vides de suite passent (scène vraiment vide). Les échecs
     * (`null`) passent avant ([accumulated]) : le chemin erreur garde déjà
     * les derniers masques. La frame rejouée est une copie : objets gardés,
     * `elapsedMs` et `truncated` frais (sinon le diagnostic lirait périmé).
     * Sans horloge : le compteur seul décide. Remis à zéro par [reset].
     */
    fun holdLastGood(raw: LiveFrame): LiveFrame {
        if (raw.objects.isNotEmpty()) {
            lastGood = raw
            misses = 0
            return raw
        }
        val held = lastGood
        if (held != null && misses < 1) {
            misses += 1
            return held.copy(elapsedMs = raw.elapsedMs, truncated = raw.truncated)
        }
        lastGood = null
        misses = 0
        return raw
    }
}

/**
 * Frame à publier : la fusion accumulée quand la frame tournait ([Send.rotated],
 * concepts capturés à l'envoi — un vol chevauché ne mélange pas les groupes),
 * le filet 1-manqué sinon ([holdLastGood]). `null` = échec, inchangé.
 */
internal fun LiveProbe.accumulated(raw: LiveFrame?, prompts: List<String>, rotated: Boolean): LiveFrame? {
    if (raw == null) return null
    if (rotated) return rotation.accumulate(prompts, raw, System.currentTimeMillis())
    return rotation.holdLastGood(raw)
}
