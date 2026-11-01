package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrases de la traversée **sans feu** : pas de « Regardez à gauche / à
 * droite » (ordre user du 31/10) — la voix dit les voitures en mouvement
 * (voix véhicules, prioritaire) ou « Traversez. » quand aucune ne bouge.
 *
 * Pur et testé en JVM.
 */
object NoLightCrossingText {

    /**
     * Ordre de traverser sans feu : aucun feu n'est vert, la phrase ne parle
     * donc d'aucune couleur (pas le « Vert, traversez. » du feu).
     */
    fun cross(language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> "Traversez."
        SpeechLanguage.En -> "Cross."
    }

    /** Ordre d'attendre sans feu : le pendant du [cross], toutes les 2 s aussi. */
    fun wait(language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> "Attendez."
        SpeechLanguage.En -> "Wait."
    }
}

/**
 * Porte « sans feu » : [NO_LIGHT_MIN_FRAMES] frames de suite sans boîte de
 * feu → pas de feu à lire, la voix sans-feu prend le relais. Toute boîte
 * réapparue rebascule aussitôt côté feu (direction sûre : un feu cadré
 * reparle avant tout).
 *
 * Pur et testé en JVM : [lightBox] est la boîte lue sur la frame (`null` =
 * aucun feu en vue).
 */
class NoLightGate(private val minFrames: Int = NO_LIGHT_MIN_FRAMES) {

    private var missing = 0

    /** Vrai une fois l'absence de feu confirmée (faux avant, et dès qu'une boîte revient). */
    var noLight: Boolean = false
        private set

    /** Avance d'une frame : [lightBox] `null` compte, sinon retour côté feu immédiat. */
    fun onFrame(lightBox: List<Float>?): Boolean {
        if (lightBox == null) missing += 1 else missing = 0
        noLight = missing >= minFrames
        return noLight
    }

    /** Oublie le compte (nouvelle traversée, nouvelle séance). */
    fun reset() {
        missing = 0
        noLight = false
    }

    companion object {
        /**
         * Frames sans boîte avant de conclure « sans feu » (≈ 0,8 s à 5 ips,
         * miroir du vert : un faux « sans feu » envoie sur la chaussée, il se
         * prouve comme un vert).
         */
        const val NO_LIGHT_MIN_FRAMES = 4
    }
}

/**
 * Annonceur du verdict sans feu : « Traversez. » sans danger, « Attendez. »
 * sur danger ([danger] vrai : véhicule brut en mouvement — une garée ne
 * bloque jamais) — redit toutes les [CROSS_REPEAT_MS] ms dans les deux cas
 * (ordre user du 31/10 : toutes les 2 s, on sait si on peut traverser). Un
 * verdict se confirme ([CROSS_MIN_FRAMES] frames stables), et le premier
 * « Traversez. » exige en plus [CALM_MS] de calme continu (ordre user du
 * 01/11 : 3 s sans voiture en mouvement — tout danger rouvre la fenêtre) ;
 * « Attendez. » coupe le délai après un « Traversez. » (miroir du rouge
 * après vert : contredire vite est sûr, attendre pour traverser aussi).
 *
 * Pur et testé en JVM : [atMs] est fourni, aucune horloge implicite.
 */
class NoLightAnnouncer(
    private val minFrames: Int = CROSS_MIN_FRAMES,
    private val repeatMs: Long = CROSS_REPEAT_MS,
    private val calmMs: Long = CALM_MS,
) {

    private var steady = 0
    private var lastDanger: Boolean? = null
    private var lastSpokenAtMs: Long? = null
    private var lastSpokenDanger: Boolean? = null
    private var calmSinceMs: Long? = null

    /**
     * Verdict à [atMs] (`null` = silence : confirmation en cours, fenêtre
     * de calme pas encore remplie, répétition pas encore due, ou la voix
     * véhicules a parlé). [vehicleSpoke] vrai quand la voix véhicules a
     * parlé sur cette frame : le verdict se tait et recule son délai (pas
     * de doublon avec la phrase prioritaire).
     */
    fun onFrame(
        atMs: Long,
        danger: Boolean,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        vehicleSpoke: Boolean = false,
    ): String? {
        if (vehicleSpoke) {
            lastSpokenAtMs = atMs
            lastSpokenDanger = danger
            lastDanger = danger
            steady = 0
            noteCalm(atMs, danger)
            return null
        }
        if (lastDanger != danger) {
            lastDanger = danger
            steady = 1
        } else {
            steady += 1
        }
        noteCalm(atMs, danger)
        if (steady < minFrames) return null
        if (!danger && !calmEnough(atMs)) return null
        val last = lastSpokenAtMs
        val interrupt = danger && lastSpokenDanger == false
        if (!interrupt && last != null && atMs - last < repeatMs) return null
        lastSpokenAtMs = atMs
        lastSpokenDanger = danger
        return if (danger) NoLightCrossingText.wait(language) else NoLightCrossingText.cross(language)
    }

    /** Fenêtre de calme : ouverte à la première frame calme, fermée par tout danger. */
    private fun noteCalm(atMs: Long, danger: Boolean) {
        if (danger) calmSinceMs = null else if (calmSinceMs == null) calmSinceMs = atMs
    }

    /** Le calme dure-t-il depuis [calmMs] sans interruption ? */
    private fun calmEnough(atMs: Long): Boolean {
        val since = calmSinceMs ?: return false
        return atMs - since >= calmMs
    }

    /** Oublie confirmation, délai, dernier verdict et fenêtre de calme (nouvelle traversée, nouvelle séance). */
    fun reset() {
        steady = 0
        lastDanger = null
        lastSpokenAtMs = null
        lastSpokenDanger = null
        calmSinceMs = null
    }

    companion object {
        /** Frames stables avant un verdict (un état isolé = bruit). */
        const val CROSS_MIN_FRAMES = 2

        /** Répétition du verdict déjà dit (« Traversez. » comme « Attendez. » : toutes les 2 s). */
        const val CROSS_REPEAT_MS = 2_000L

        /** Calme continu exigé avant le premier « Traversez. » (3 s sans voiture en mouvement). */
        const val CALM_MS = 3_000L
    }
}
