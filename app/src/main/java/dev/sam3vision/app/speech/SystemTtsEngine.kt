package dev.sam3vision.app.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Locale
import java.util.UUID

/**
 * Moteur de synthèse derrière [SystemTtsEngine] : [AndroidTtsDriver] en
 * production (vrai `TextToSpeech`), un faux en test JVM (aucun Android).
 */
interface TtsDriver {
    /**
     * Prononce [text] : [flush] vrai coupe la phrase en cours (`QUEUE_FLUSH`),
     * faux met en file derrière (`QUEUE_ADD`, jamais de chevauchement).
     * [locale], [rate] et [pitch] sont appliqués avant de parler ([pitch] 1 =
     * voix normale, [SystemTtsEngine.SAFETY_PITCH] = voix de sécurité, grave).
     * Renvoie faux si refusé.
     */
    fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float = 1f): Boolean

    /** Coupe la parole (file vidée, moteur gardé). */
    fun stop()

    /**
     * Libération finale (implique l'arrêt de la parole en cours) : le pilote
     * n'accepte plus rien ensuite.
     */
    fun shutdown()

    /** Une phrase est-elle en cours de lecture ? */
    val speaking: Boolean
}

/**
 * Voix système Android (`android.speech.tts.TextToSpeech`) pour le guidage :
 * gratuite, hors-ligne, en anglais — là où [dev.sam3vision.app.VoiceAnnouncer]
 * parle ElevenLabs (clé, réseau, latence), un virage ne peut pas attendre.
 *
 * File sans chevauchement : un seul moteur, les phrases normales s'enchaînent
 * (`QUEUE_ADD`), les urgentes coupent (`QUEUE_FLUSH`). Le débit est borné par
 * [Settings.clampTtsSpeed], comme la voix ElevenLabs.
 *
 * Sûr pour le cycle de vie : ne retient que le contexte applicatif (jamais une
 * activité), [close] est idempotent (appelé depuis `onCleared`/`onDestroy`),
 * tout appel après [close] est refusé sans lever, et un pilote qui jette est
 * replié en refus — cette classe ne lève jamais.
 *
 * Testée en JVM via un [TtsDriver] faux ([create] seul touche Android).
 */
class SystemTtsEngine(private val driver: TtsDriver) {

    @Volatile
    private var closed = false

    /**
     * Prononce [text] : [urgent] coupe la phrase en cours, sinon mise en file.
     * [pitch] grave ([SAFETY_PITCH]) = voix de sécurité, 1 = voix normale.
     * Faux si le texte est blanc, le moteur fermé, ou le pilote en échec.
     */
    fun speak(text: String, urgent: Boolean, locale: Locale, rate: Float, pitch: Float = 1f): Boolean {
        if (closed || text.isBlank()) return false
        val clean = text.trim()
        if (clean.isEmpty()) return false
        return runCatching { driver.speak(clean, urgent, locale, Settings.clampTtsSpeed(rate), clampPitch(pitch)) }
            .getOrDefault(false)
    }

    /** Coupe la parole en cours (écran quitté, nouvel ordre). Sans effet si fermé. */
    fun stop() {
        if (closed) return
        runCatching { driver.stop() }
    }

    /**
     * Libération finale, idempotente ([shutdown] coupe déjà la parole). Tout appel
     * ultérieur ([speak], [stop], [speaking]) est refusé ou vide, sans lever.
     */
    fun close() {
        if (closed) return
        closed = true
        runCatching { driver.shutdown() }
    }

    /** Vrai si une phrase est en cours de lecture (faux si fermé ou en échec). */
    val speaking: Boolean
        get() = !closed && runCatching { driver.speaking }.getOrDefault(false)

    companion object {
        /**
         * Moteur branché sur le vrai TTS système. [context] est réduit à son
         * contexte applicatif : retenir une activité fuirait à la rotation.
         */
        fun create(context: Context): SystemTtsEngine =
            SystemTtsEngine(AndroidTtsDriver(context.applicationContext))

        /** Langue de la voix → locale du moteur (anglais par défaut). */
        fun localeOf(language: SpeechLanguage): Locale = when (language) {
            SpeechLanguage.Fr -> Locale.FRENCH
            SpeechLanguage.En -> Locale.ENGLISH
        }

        /**
         * Signature de la voix de sécurité : grave (0,75), distincte de la
         * voix route (1,0, même moteur) comme de Laure (ElevenLabs) — une
         * urgence ne sonne jamais comme une phrase normale (ordre user).
         */
        const val SAFETY_PITCH = 0.75f

        /** Borne le pitch 0,5..2 (`TextToSpeech` exige > 0, 1 = normal). */
        fun clampPitch(pitch: Float): Float = pitch.coerceIn(0.5f, 2f)
    }
}

/**
 * Pilote [TextToSpeech] : initialisation asynchrone avec tampon borné.
 *
 * Le moteur met quelques centaines de millisecondes à s'initialiser : les
 * phrases prononcées avant sont gardées ([PENDING_MAX], les plus vieilles
 * tombent, une urgente vide le tampon) puis parlées dans l'ordre à l'arrivée
 * de [onInit]. Échec d'init (`CHECK_VOICE_DATA` manquant…) = pilote condamné :
 * tout est jeté et [speak] refuse ensuite, sans lever.
 *
 * Adaptateur fin, comme [dev.sam3vision.app.SpeechPlayer] : instanciable
 * uniquement sur Android, donc sans test JVM — la file et le cycle de vie
 * sont testés dans [SystemTtsEngine] derrière un faux.
 */
class AndroidTtsDriver(appContext: Context) : TtsDriver, TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(appContext, this)
    private val lock = Any()

    @Volatile
    private var ready = false

    @Volatile
    private var down = false

    private val pending = ArrayDeque<Pending>()

    /** Phrase en attente d'initialisation : texte, coupe, locale, débit, pitch. */
    private data class Pending(val text: String, val flush: Boolean, val locale: Locale, val rate: Float, val pitch: Float)

    override fun onInit(status: Int) {
        val ok = status == TextToSpeech.SUCCESS
        val backlog: List<Pending>
        synchronized(lock) {
            if (ok) ready = true else down = true
            backlog = pending.toList()
            pending.clear()
        }
        if (ok) backlog.forEach { utter(it.text, it.flush, it.locale, it.rate, it.pitch) }
    }

    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean {
        if (down || text.isBlank()) return false
        synchronized(lock) {
            if (!ready) {
                if (flush) pending.clear()
                if (pending.size >= PENDING_MAX) pending.removeFirst()
                pending.add(Pending(text, flush, locale, rate, pitch))
                return true
            }
        }
        return utter(text, flush, locale, rate, pitch)
    }

    override fun stop() {
        synchronized(lock) { pending.clear() }
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        down = true
        synchronized(lock) { pending.clear() }
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    override val speaking: Boolean
        get() = !down && runCatching { tts.isSpeaking }.getOrDefault(false)

    /** Parole immédiate : langue + débit + pitch appliqués, puis file ou coupe. */
    private fun utter(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean =
        runCatching {
            runCatching { tts.language = locale }
            runCatching { tts.setSpeechRate(Settings.clampTtsSpeed(rate)) }
            runCatching { tts.setPitch(SystemTtsEngine.clampPitch(pitch)) }
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(text, mode, Bundle(), UUID.randomUUID().toString()) == TextToSpeech.SUCCESS
        }.getOrDefault(false)

    companion object {
        /** Phrases gardées avant l'init : au-delà, les plus vieilles tombent. */
        const val PENDING_MAX = 5
    }
}
