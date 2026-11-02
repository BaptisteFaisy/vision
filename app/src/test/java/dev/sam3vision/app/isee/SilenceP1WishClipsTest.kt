package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbEdge
import dev.sam3vision.core.curb.CurbText
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 silence T-B1 : les vœux dynamiques non-GPS portent leur séquence de
 * clips (jouée par `BusSpeaker.say`, jamais synthétisée), texte inchangé.
 * L'urgent garde son clip seul (la séquence l'éclipserait : vide ici), le
 * FR et les formes sans atome restent vides (silence documenté, pas de
 * mensonge — voir `DynamicClipChain`).
 */
class SilenceP1WishClipsTest {

    @Test
    fun `barriere EN avec heure = objet + cadran, texte inchange`() {
        val wish = hazardWish(
            HazardCue(
                "Attention, barrier, at 2 o'clock.", meters = 4f,
                kind = HazardKind.BARRIER, level = OutdoorLevel.FENCE, antiRepeatKey = AntiRepeat.KEY_FENCE,
            ),
        )

        assertEquals("Attention, barrier, at 2 o'clock.", wish?.text)
        assertEquals(listOf(IseeClipsEn.BARRIER, IseeClipsEn.CLOCK_2), wish?.clips)
        assertNull(wish?.clipId)
    }

    @Test
    fun `cloture EN sans heure = objet seul`() {
        val wish = hazardWish(
            HazardCue(
                "Attention, fence.", meters = 5f,
                kind = HazardKind.OTHER, level = OutdoorLevel.FENCE, antiRepeatKey = AntiRepeat.KEY_FENCE,
            ),
        )

        assertEquals(listOf(IseeClipsEn.FENCE), wish?.clips)
    }

    @Test
    fun `personne EN = personne + cadran + distance ronde`() {
        val wish = hazardWish(HazardCue("Attention, person, at 3 o'clock.", meters = 8f, kind = HazardKind.PERSON))

        assertEquals(
            listOf(IseeClipsEn.HUMAN_PERSON, IseeClipsEn.CLOCK_3, IseeClipsEn.METERS_10),
            wish?.clips,
        )
    }

    @Test
    fun `mot imminent urgent = clip seul, sequence vide`() {
        val wish = hazardWish(
            HazardCue(
                "Right.", meters = 1f, urgent = true,
                kind = HazardKind.PERSON, antiRepeatKey = AntiRepeat.KEY_HUMAN_IMMINENT,
            ),
        )

        assertEquals("guide_right", wish?.clipId)
        assertTrue(wish?.clips?.isEmpty() == true)
    }

    @Test
    fun `danger FR = silence, pas de clips anglais`() {
        val wish = hazardWish(
            HazardCue(
                "Attention à la barrière, à 2 heures.", meters = 2f,
                kind = HazardKind.BARRIER, level = OutdoorLevel.FENCE, antiRepeatKey = AntiRepeat.KEY_FENCE,
            ),
        )

        assertTrue(wish?.clips?.isEmpty() == true)
    }

    @Test
    fun `nom sans atome = silence`() {
        val wish = hazardWish(
            HazardCue(
                "Attention, souvenir shop, at 2 o'clock.", meters = 2f,
                kind = HazardKind.OTHER, level = OutdoorLevel.POLE, antiRepeatKey = AntiRepeat.KEY_POLE,
            ),
        )

        assertTrue(wish?.clips?.isEmpty() == true)
    }

    @Test
    fun `detection EN sens connu = sens + cadran, texte inchange`() {
        val phrase = CurbText.detectedPhrase(SpeechLanguage.En, 0.5f)
        val wish = curbWish(CurbCue(phrase, meters = 3f, tier = CurbTier.FAR, edge = CurbEdge.Down))

        assertEquals("Curb detected, at 12 o'clock.", wish?.text)
        assertEquals(listOf(IseeClipsEn.CURB_DOWN, IseeClipsEn.CLOCK_12), wish?.clips)
    }

    @Test
    fun `compte a rebours EN sens connu = sens seul, texte inchange`() {
        val phrase = CurbText.distancePhrase(2f, SpeechLanguage.En)
        val wish = curbWish(CurbCue(phrase, meters = 2f, tier = CurbTier.FAR, edge = CurbEdge.Up))

        assertEquals("Curb at 2 meters.", wish?.text)
        assertEquals(listOf(IseeClipsEn.CURB_UP), wish?.clips)
    }

    @Test
    fun `sens incertain = silence, quel que soit le texte`() {
        val en = SpeechLanguage.En
        assertTrue(curbWish(CurbCue(CurbText.detectedPhrase(en, 0.5f), meters = 3f, edge = CurbEdge.Uncertain))?.clips?.isEmpty() == true)
        assertTrue(curbWish(CurbCue(CurbText.stepPhrase(CurbEdge.Uncertain, en), meters = 0.7f))?.clips?.isEmpty() == true)
        // Défaut : une construction manuelle sans sens se tait elle aussi.
        assertTrue(curbWish(CurbCue(CurbText.detectedPhrase(en, null), meters = 3f))?.clips?.isEmpty() == true)
    }

    @Test
    fun `bordure FR sens connu = silence, zero clip FR`() {
        val fr = SpeechLanguage.Fr
        assertTrue(curbWish(CurbCue(CurbText.detectedPhrase(fr, 0.5f), meters = 3f, tier = CurbTier.FAR, edge = CurbEdge.Up))?.clips?.isEmpty() == true)
        assertTrue(curbWish(CurbCue(CurbText.distancePhrase(2f, fr), meters = 2f, edge = CurbEdge.Down))?.clips?.isEmpty() == true)
    }

    @Test
    fun `nuls et filtre traversee inchanges`() {
        assertNull(hazardWish(null))
        assertNull(curbWish(null))
        assertNull(
            hazardWish(
                HazardCue(
                    "Attention, barrier, at 2 o'clock.", meters = 2f,
                    kind = HazardKind.BARRIER, level = OutdoorLevel.FENCE, antiRepeatKey = AntiRepeat.KEY_FENCE,
                ),
                crossingActive = true,
            ),
        )
    }
}
