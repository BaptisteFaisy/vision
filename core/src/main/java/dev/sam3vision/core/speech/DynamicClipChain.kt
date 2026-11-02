package dev.sam3vision.core.speech

/**
 * Chaînes MP3 des phrases dynamiques non-GPS (P1 silence SPEC v4) : la porte
 * GPS de `ProximityVoice` tait toute phrase dynamique non-GPS sans clip — la
 * correction SPEC-compatible émet des séquences de clips, jouées en file par
 * `BusSpeaker.say` (`speakSequence`, déjà câblé), jamais synthétisées.
 *
 * Anglais seul, comme [dev.sam3vision.core.stairs.StairChain] (le pack ne
 * porte que des clips EN) : toute autre langue — lue sur la forme de la
 * phrase, qui porte déjà sa langue — rend une liste vide (silence, jamais
 * de synthèse). Pur Kotlin, testé en JVM, aucune dépendance Android.
 *
 * Règle d'or : une chaîne ne ment jamais — quand un atome manque (pas de
 * « mètre » seul, pas de sens de bordure dans la phrase), la forme rend une
 * liste vide (silence d'aujourd'hui, pas de régression) au lieu d'inventer.
 * Les distances rondes ([IseeClipsEn.METERS_5]/10/15/20, prévues pour « les
 * humains dehors ») sont des arrondis au plus proche ([metersClip], prior).
 */
object DynamicClipChain {

    /** Objet nommé + heure lues dans une phrase « Attention … » anglaise. */
    data class HazardParts(val objectClip: String, val hour: Int?)

    /** « Attention, barrier[, at 2 o'clock]. » : nom (insensible à la casse) + heure optionnelle. */
    private val HAZARD_EN = Regex("^Attention, ([A-Za-z ]+?)(?:, at (\\d{1,2}) o'clock)?\\.$")

    /** « …, at 2 o'clock… » : l'heure du cadran, où qu'elle soit dans la phrase. */
    private val HOUR_EN = Regex("at (\\d{1,2}) o'clock")

    /**
     * Nom anglais du danger → atome : l'exact quand il existe, l'hyperonyme
     * vrai sinon (le chat et le chien sont des animaux, l'enfant une
     * personne) — jamais d'approximation (poussette, fauteuil, benne,
     * boutique, bonnet, chaussettes : pas d'atome, silence).
     */
    private val OBJECT_CLIPS: Map<String, String> = mapOf(
        "barrier" to IseeClipsEn.BARRIER,
        "fence" to IseeClipsEn.FENCE,
        "wall" to IseeClipsEn.WALL,
        "pole" to IseeClipsEn.POLE,
        "street lamp" to IseeClipsEn.LAMP,
        "sign" to IseeClipsEn.SIGN,
        "traffic sign" to IseeClipsEn.SIGN,
        "animal" to IseeClipsEn.ANIMAL,
        "cat" to IseeClipsEn.ANIMAL,
        "dog" to IseeClipsEn.ANIMAL,
        "trash can" to IseeClipsEn.BIN,
        "person" to IseeClipsEn.HUMAN_PERSON,
        "child" to IseeClipsEn.HUMAN_PERSON,
    )

    /**
     * « Personne + cadran + distance » : [human_person, clock_N?, meters_M?].
     * L'heure hors 1..12 tombe (objet seul), la distance hors mesure aussi —
     * la chaîne dit ce qu'elle sait, jamais plus. Vide hors anglais.
     */
    fun personClips(hour: Int?, meters: Float?, language: SpeechLanguage): List<String> {
        if (language != SpeechLanguage.En) return emptyList()
        val chain = mutableListOf(IseeClipsEn.HUMAN_PERSON)
        if (hour != null) IseeClipsEn.clock(hour)?.let { chain += it }
        metersClip(meters)?.let { chain += it }
        return chain
    }

    /**
     * Objet + heure lus dans [phrase] (« Attention, {nom}[, at N o'clock]. »),
     * `null` si la forme n'est pas anglaise connue (FR, nom sans atome,
     * heure mal formée : silence, comme aujourd'hui).
     */
    fun hazardParts(phrase: String): HazardParts? {
        val match = HAZARD_EN.matchEntire(phrase) ?: return null
        val clip = OBJECT_CLIPS[match.groupValues[1].trim().lowercase()] ?: return null
        val hour = match.groupValues[2].takeIf { it.isNotEmpty() }
            ?.toIntOrNull()?.takeIf { it in 1..12 }
        return HazardParts(clip, hour)
    }

    /**
     * Chaîne d'un vœu danger non urgent : la personne dit personne + cadran
     * + distance ([personClips]), les autres dangers objet + cadran (leur
     * phrase ne porte pas de distance : on n'en invente pas). Vide si la
     * phrase n'est pas une forme anglaise connue.
     */
    fun hazardClips(parts: HazardParts, meters: Float?, language: SpeechLanguage): List<String> {
        if (language != SpeechLanguage.En) return emptyList()
        if (parts.objectClip == IseeClipsEn.HUMAN_PERSON) return personClips(parts.hour, meters, language)
        val chain = mutableListOf(parts.objectClip)
        parts.hour?.let { IseeClipsEn.clock(it) }?.let { chain += it }
        return chain
    }

    /**
     * Chaîne d'un vœu danger lue dans sa phrase + sa distance ([hazardParts]
     * puis [hazardClips]) : l'entrée des vœux, qui n'ont que la phrase. Vide
     * hors forme anglaise connue — l'urgent n'y passe jamais (son mot part
     * en clip seul, que la séquence éclipserait).
     */
    fun hazardWishClips(phrase: String, meters: Float?): List<String> {
        val parts = hazardParts(phrase) ?: return emptyList()
        return hazardClips(parts, meters, SpeechLanguage.En)
    }

    /**
     * Chaîne d'un vœu bordure : « Step up… » → [curb_up, clock_N?], « Step
     * down… » → [curb_down, clock_N?]. Le reste (« Curb detected… », « Curb
     * at… », « Attention, curb… », tout le FR) ne dit pas le sens du bord :
     * vide (pas de mensonge — le vrai fix ajoute le sens au signal, voir
     * le rapport P1). La distance sous 5 m tombe (pas d'atome « mètre »).
     */
    fun curbClips(phrase: String): List<String> {
        val edge = when {
            phrase.startsWith("Step up") -> IseeClipsEn.CURB_UP
            phrase.startsWith("Step down") -> IseeClipsEn.CURB_DOWN
            else -> return emptyList()
        }
        val chain = mutableListOf(edge)
        hourOfEn(phrase)?.let { IseeClipsEn.clock(it) }?.let { chain += it }
        return chain
    }

    /** Heure du cadran lue dans une phrase anglaise, `null` si absente ou hors 1..12. */
    fun hourOfEn(phrase: String): Int? =
        HOUR_EN.find(phrase)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..12 }

    /**
     * Distance ronde parlée : 5, 10, 15 ou 20 m au plus proche (frontières
     * 7,5 / 12,5 / 17,5 — prior, à valider rue), `null` sans mesure. Les
     * paliers proches (< 5 m) disent 5 m : un arrondi assumé, comme l'écran.
     */
    fun metersClip(meters: Float?): String? {
        if (meters == null || !meters.isFinite() || meters <= 0f) return null
        return when {
            meters < 7.5f -> IseeClipsEn.METERS_5
            meters < 12.5f -> IseeClipsEn.METERS_10
            meters < 17.5f -> IseeClipsEn.METERS_15
            else -> IseeClipsEn.METERS_20
        }
    }
}
