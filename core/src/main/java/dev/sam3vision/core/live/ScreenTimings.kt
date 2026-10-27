package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.net.WireMarks

/**
 * Chronologie d'un écran (Direct ou iSee) : les postes de la frame en cours, la garde du
 * premier pixel et l'instantané lu par l'UI. Isolée ici pour que les deux écrans partagent
 * exactement la même logique — et pour garder les ViewModel sous la limite de lignes.
 *
 * **Une chronologie par frame, pas une seule partagée.** La frame suivante est dispatchée
 * par `drain()` dans la même chaîne de coroutines que la publication de la précédente : si
 * `beginFrame` remettait à zéro le seul objet existant, le poste `PIXEL` — posé ~16 ms plus
 * tard par [noteDrawn], quand Compose a réellement dessiné — tomberait sur une frame déjà
 * repartie, et le garde `overlaysReady` serait déjà retombé à `false`. Mesuré sur le Redmi
 * le 2026-10-26 : `pixel —` sur **12 relevés sur 12**, avec un objet affiché à chaque fois.
 *
 * Chaque frame a donc son propre [LiveTimings] ; [drawn] garde la référence de la frame
 * publiée en attente de son premier pixel, et survit au `beginFrame` suivant.
 */
class ScreenTimings(private val clock: () -> Long = System::nanoTime) : WireMarks {

    /** Marques de la frame **en cours** : remplacées à chaque `beginFrame`. */
    private var marks = LiveTimings(clock)

    /** Frame dont les masques sont prêts à dessiner : c'est elle qui recevra le poste PIXEL. */
    @Volatile
    private var drawn: LiveTimings? = null

    /** Nouvelle frame : les postes de tête sont posés d'un coup ([encodedAtNs] = 0 = maintenant). */
    fun beginFrame(captureAtNs: Long = 0L, encodedAtNs: Long = 0L) {
        val fresh = LiveTimings(clock)
        if (captureAtNs > 0L) fresh.mark(LiveStage.CAPTURE, captureAtNs)
        fresh.mark(LiveStage.ENCODED, if (encodedAtNs > 0L) encodedAtNs else clock())
        fresh.mark(LiveStage.SENT)
        marks = fresh
    }

    /** Réponse complète (corps lu et JSON tourné). */
    fun markReceived() = marks.mark(LiveStage.RECEIVED)

    /**
     * En-têtes de la réponse reçus : c'est ce qui sépare l'attente (`envoi → en-têtes`) de
     * la descente du corps. Appelé par le client réseau ([WireMarks.onHeaders]).
     */
    fun markHeaders() = marks.mark(LiveStage.HEADERS)

    /** Corps complet reçu, **avant** le parsing JSON ([WireMarks.onBody]). */
    fun markBody() = marks.mark(LiveStage.BODY)

    /** Le réseau nous prévient : même chemin que [markHeaders], interface du `core`. */
    override fun onHeaders() = markHeaders()

    /** Idem pour le corps : [onBody] ne fait que nommer [markBody] côté client réseau. */
    override fun onBody() = markBody()

    /**
     * La frame dont la réponse est **reçue** devient la frame publiée : elle est détachée
     * du vol en cours et rendue, pour que son rendu (masques, premier pixel) continue de
     * poser ses propres marques après le `beginFrame` de la suivante.
     */
    fun publishing(): LiveTimings {
        drawn = marks
        return marks
    }

    /** Masques de la frame publiée ([publishing]) : rien à voir avec la frame en vol. */
    fun markOverlays(on: LiveTimings) {
        on.mark(LiveStage.OVERLAYS)
    }

    /** Instantané d'une frame précise (et non de celle qui est en vol). */
    fun snapshot(on: LiveTimings, frame: LiveFrame?): FrameTimings =
        on.snapshot(frame?.elapsedMs ?: -1L, frame?.timingsMs.orEmpty())

    /** Masques décodés et coloriés : la frame est candidate au poste « premier pixel ». */
    fun markOverlays() {
        markOverlays(marks)
        drawn = marks
    }

    /** Sortie d'écran, nouvelle session, début d'enregistrement : plus aucune marque. */
    fun reset() {
        marks = LiveTimings(clock)
        drawn = null
    }

    /**
     * Vrai si le poste « pixel » vient d'être posé — l'UI doit publier l'instantané.
     *
     * Rien n'est posé tant qu'aucune frame n'a ses masques prêts ([drawn] vide) : sans
     * masque, il n'y a rien eu à dessiner, et une latence « perçue » inventée vaudrait
     * mieux que pas de mesure du tout — elle vaut moins que rien.
     */
    fun noteDrawn(): Boolean {
        val frame = drawn ?: return false
        frame.mark(LiveStage.PIXEL)
        return true
    }

    /** Instantané de la frame en cours : l'UI ne lit jamais les marques, seulement ceci. */
    fun snapshot(frame: LiveFrame? = null): FrameTimings =
        marks.snapshot(frame?.elapsedMs ?: -1L, frame?.timingsMs.orEmpty())

    /**
     * Instantané après coup (poste « pixel ») : les chiffres serveur viennent de l'instantané
     * d'avant, les marques de la frame **dessinée** — qui n'est plus la frame en cours quand
     * le pipeline a déjà entamé la suivante.
     */
    fun snapshot(previous: FrameTimings): FrameTimings =
        (drawn ?: marks).snapshot(previous.serverTotalMs, previous.server)
}
