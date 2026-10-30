package dev.sam3vision.app.isee

import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bascule auto du mode Traversée par la voix : armement dit une fois par
 * apparition du passage, retour au normal dit après sa perte. Une personne
 * aveugle n'a que la voix — si l'annonce se répète à chaque frame, elle
 * devient inaudible.
 *
 * La perte compte des **frames** (une émission = une frame publiée, comme
 * l'état sonde qui change à chaque frame) : le test émet sur un flux
 * partagé, qu'un [MutableStateFlow] confluerait à valeur égale.
 */
class CrossingAutoVoiceTest {

    @Test
    fun `le passage qui apparait arme le mode et le dit une fois`() = runTest {
        val offers = MutableStateFlow(false)
        val armed = mutableListOf<Boolean>()
        val said = mutableListOf<String>()
        observeCrossingAuto(offers, backgroundScope, { SpeechLanguage.Fr }, { armed.add(it) }) { said.add(it) }

        offers.value = false
        offers.value = true
        offers.value = true
        offers.value = true
        runCurrent()               // le collecteur vit sur le dispatcher de test

        assertEquals(listOf(true), armed)
        assertEquals(listOf("Mode traversée activé."), said)
    }

    @Test
    fun `le passage qui revient apres une disparition rearme`() = runTest {
        val offers = MutableStateFlow(false)
        val armed = mutableListOf<Boolean>()
        val said = mutableListOf<String>()
        observeCrossingAuto(offers, backgroundScope, { SpeechLanguage.Fr }, { armed.add(it) }) { said.add(it) }

        offers.value = true
        runCurrent()
        offers.value = false
        runCurrent()
        offers.value = true
        runCurrent()

        assertEquals(listOf(true, true), armed)
        assertEquals(2, said.size)
    }

    @Test
    fun `la langue suit les reglages`() = runTest {
        val offers = MutableStateFlow(false)
        val said = mutableListOf<String>()
        observeCrossingAuto(offers, backgroundScope, { SpeechLanguage.En }, {}) { said.add(it) }

        offers.value = true
        runCurrent()

        assertEquals(listOf("Crossing mode on."), said)
    }

    @Test
    fun `quatre frames sans passage coupent le mode arme par la vision`() = runTest {
        val states = MutableSharedFlow<Pair<Boolean, Boolean>>()
        val armed = mutableListOf<Boolean>()
        val said = mutableListOf<String>()
        observeCrossingLoss(states, backgroundScope, { SpeechLanguage.Fr }, { true }, { armed.add(it) }) { said.add(it) }

        states.emit(true to true)
        runCurrent()
        repeat(3) {
            states.emit(true to false)
            runCurrent()
        }
        assertTrue("3 frames vides : le mode tient", armed.isEmpty())
        states.emit(true to false)
        runCurrent()

        assertEquals(listOf(false), armed)
        assertEquals(listOf("Mode traversée coupé."), said)
    }

    @Test
    fun `une reapparition remet le compteur de perte a zero`() = runTest {
        val states = MutableSharedFlow<Pair<Boolean, Boolean>>()
        val armed = mutableListOf<Boolean>()
        observeCrossingLoss(states, backgroundScope, { SpeechLanguage.Fr }, { true }, { armed.add(it) }) {}

        repeat(3) {
            states.emit(true to false)
            runCurrent()
        }
        states.emit(true to true)
        runCurrent()
        repeat(3) {
            states.emit(true to false)
            runCurrent()
        }

        assertTrue(armed.isEmpty())
    }

    @Test
    fun `le mode manuel ne retombe jamais seul`() = runTest {
        val states = MutableSharedFlow<Pair<Boolean, Boolean>>()
        val armed = mutableListOf<Boolean>()
        val said = mutableListOf<String>()
        observeCrossingLoss(states, backgroundScope, { SpeechLanguage.Fr }, { false }, { armed.add(it) }) { said.add(it) }

        repeat(6) {
            states.emit(true to false)
            runCurrent()
        }

        assertTrue(armed.isEmpty())
        assertTrue(said.isEmpty())
    }

    @Test
    fun `le mode coupe remet le compteur de perte a zero`() = runTest {
        val states = MutableSharedFlow<Pair<Boolean, Boolean>>()
        val armed = mutableListOf<Boolean>()
        observeCrossingLoss(states, backgroundScope, { SpeechLanguage.Fr }, { true }, { armed.add(it) }) {}

        repeat(3) {
            states.emit(true to false)
            runCurrent()
        }
        states.emit(false to false)
        runCurrent()
        repeat(3) {
            states.emit(true to false)
            runCurrent()
        }

        assertTrue("le passage par l'inactif a remis le compteur à zéro", armed.isEmpty())
    }
}
