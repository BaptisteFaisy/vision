package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbEdge
import dev.sam3vision.core.curb.CurbText
import dev.sam3vision.core.hazard.HazardConcepts
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.hazard.HazardText
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 silence T-B3 : le corpus des phrases dynamiques non-GPS (produites
 * par les vrais textes — [HazardText], [CurbText], jamais recopiées) rend
 * zéro FAIL sans-clip : toute forme anglaise chaînable porte sa séquence,
 * les autres (FR, nom sans atome, bordure sans sens) restent vides — vide
 * documenté, pas FAIL.
 */
class SilenceP1DynamicCorpusTest {

    /** Cas du corpus : [attendus] vide = silence documenté (motif dans [nom]). */
    private data class Cas(
        val nom: String,
        val phrase: String,
        val metres: Float?,
        val attendus: List<String>,
        val kind: HazardKind = HazardKind.OTHER,
    )

    @Test
    fun `corpus dangers EN = zero FAIL sans-clip`() =
        verifie(corpusDangers()) { cas ->
            hazardWish(HazardCue(cas.phrase, meters = cas.metres, kind = cas.kind))?.clips
        }

    @Test
    fun `corpus bordures EN = zero FAIL sans-clip`() =
        verifie(corpusBordures()) { cas -> curbWish(CurbCue(cas.phrase, meters = cas.metres))?.clips }

    /** Zéro écart entre clips obtenus et attendus sur tout le corpus, sinon la liste des FAIL. */
    private fun verifie(corpus: List<Cas>, clipsDe: (Cas) -> List<String>?) {
        val echecs = mutableListOf<String>()
        for (cas in corpus) {
            val obtenus = clipsDe(cas) ?: emptyList()
            if (obtenus != cas.attendus) echecs += "${cas.nom} : « ${cas.phrase} » -> $obtenus, attendu ${cas.attendus}"
        }
        assertTrue("FAIL sans-clip (${echecs.size}) : $echecs", echecs.isEmpty())
    }

    /** Dangers : 13 noms × 4 géométries (dont sans heure), plus FR et noms sans atome. */
    private fun corpusDangers(): List<Cas> {
        val etiquettes = listOf(
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
        val cas = mutableListOf<Cas>()
        for ((etiquette, atome) in etiquettes) {
            val nom = requireNotNull(HazardConcepts.nameOf(etiquette)) { etiquette }
            val kind = HazardConcepts.kindOf(etiquette)
            for (cx in listOf(null, 0.1f, 0.5f, 0.9f)) {
                val phrase = HazardText.phrase(nom, SpeechLanguage.En, cx)
                val attendus = mutableListOf(atome)
                if (cx != null) attendus += requireNotNull(IseeClipsEn.clock(PositionText.clockHour(cx)))
                if (atome == IseeClipsEn.HUMAN_PERSON) attendus += IseeClipsEn.METERS_5
                cas += Cas("$etiquette cx=$cx", phrase, 4f, attendus, kind)
            }
        }
        cas += Cas(
            "FR barrière (silence documenté : atomes EN seuls)",
            HazardText.phrase(requireNotNull(HazardConcepts.nameOf("barrier")), SpeechLanguage.Fr, 0.5f),
            2f, emptyList(), HazardKind.BARRIER,
        )
        cas += Cas(
            "FR personne (silence documenté : atomes EN seuls)",
            HazardText.phrase(requireNotNull(HazardConcepts.nameOf("person")), SpeechLanguage.Fr, null),
            3f, emptyList(), HazardKind.PERSON,
        )
        for (etiquette in listOf("stroller", "wheelchair", "dumpster", "souvenir shop", "beanie", "socks")) {
            val nom = requireNotNull(HazardConcepts.nameOf(etiquette)) { etiquette }
            val phrase = HazardText.phrase(nom, SpeechLanguage.En, 0.5f)
            cas += Cas("$etiquette (silence documenté : pas d'atome)", phrase, 2f, emptyList(), HazardConcepts.kindOf(etiquette))
        }
        return cas
    }

    /** Bordures : sensées (chaînées) contre détection/compte/FR (silence documenté). */
    private fun corpusBordures(): List<Cas> {
        val en = SpeechLanguage.En
        val fr = SpeechLanguage.Fr
        fun horloge(cx: Float) = requireNotNull(IseeClipsEn.clock(PositionText.clockHour(cx)))
        return listOf(
            Cas("montée 1m + heure", CurbText.phrase(CurbEdge.Up, en, 0.5f), 1f, listOf(IseeClipsEn.CURB_UP, horloge(0.5f))),
            Cas("descente 1m + heure", CurbText.phrase(CurbEdge.Down, en, 0.1f), 1f, listOf(IseeClipsEn.CURB_DOWN, horloge(0.1f))),
            Cas("descente 1m sans heure", CurbText.phrase(CurbEdge.Down, en, null), 1f, listOf(IseeClipsEn.CURB_DOWN)),
            Cas("sens incertain (silence : sens inconnu)", CurbText.phrase(CurbEdge.Uncertain, en, 0.5f), 1f, emptyList()),
            Cas("marche imminente", CurbText.stepPhrase(CurbEdge.Up, en), 0.7f, listOf(IseeClipsEn.CURB_UP)),
            Cas("descente imminente", CurbText.stepPhrase(CurbEdge.Down, en), 0.7f, listOf(IseeClipsEn.CURB_DOWN)),
            Cas("imminente incertaine (silence : sens inconnu)", CurbText.stepPhrase(CurbEdge.Uncertain, en), 0.7f, emptyList()),
            Cas("consigne montée", CurbText.safetyStepPhrase(CurbEdge.Up, en), 0.5f, listOf(IseeClipsEn.CURB_UP)),
            Cas("consigne descente", CurbText.safetyStepPhrase(CurbEdge.Down, en), 0.5f, listOf(IseeClipsEn.CURB_DOWN)),
            Cas("consigne incertaine (silence : sens inconnu)", CurbText.safetyStepPhrase(CurbEdge.Uncertain, en), 0.5f, emptyList()),
            Cas("détection + heure (silence : sens inconnu)", CurbText.detectedPhrase(en, 0.5f), 3f, emptyList()),
            Cas("détection (silence : sens inconnu)", CurbText.detectedPhrase(en, null), 3f, emptyList()),
            Cas("compte 2m (silence : sens inconnu)", CurbText.distancePhrase(2f, en), 2f, emptyList()),
            Cas("compte 1m (silence : sens inconnu)", CurbText.distancePhrase(1f, en), 1f, emptyList()),
            Cas("compte 0,5m (silence : sens inconnu)", CurbText.distancePhrase(0.5f, en), 0.5f, emptyList()),
            Cas("FR montée (silence : atomes EN seuls)", CurbText.phrase(CurbEdge.Up, fr, 0.5f), 1f, emptyList()),
            Cas("FR imminente (silence : atomes EN seuls)", CurbText.stepPhrase(CurbEdge.Down, fr), 0.7f, emptyList()),
            Cas("FR détection (silence : atomes EN seuls)", CurbText.detectedPhrase(fr, null), 3f, emptyList()),
            Cas("FR compte (silence : atomes EN seuls)", CurbText.distancePhrase(2f, fr), 2f, emptyList()),
        )
    }
}
