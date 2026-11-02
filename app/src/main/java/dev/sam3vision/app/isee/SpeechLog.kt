package dev.sam3vision.app.isee

/**
 * Journal des phrases parlées iSee (tag `ISEE_VOIX`, étape 1a) : chaque
 * phrase décidée est attendue (`expect`, voie = FENCE, repli, GPS…),
 * jouée (`play`, source = clip, synthèse, bus), terminée (`done`) ou
 * ratée (`fail`, lieu + cause). `scripts/parse_isee_voix.py` compte
 * les EXPECT sans PLAY ni DONE par phrase.
 *
 * Ligne = un mot-clé puis des champs `clé=valeur` (valeurs à espaces
 * entre crochets) : `EXPECT voie=FENCE phrase=[Stop.]`. Les `line*`
 * sont pures (JVM) ; [sink] envoie au logcat (remplacé sous test :
 * jamais d'appel direct à `Log` sous test).
 */
object SpeechLog {
    /** Tag logcat du journal des phrases (`adb logcat -s ISEE_VOIX`). */
    const val TAG = "ISEE_VOIX"

    /** Envoi des lignes (logcat en prod, capturé sous test). */
    @Volatile
    var sink: (String, String) -> Unit = { tag, line -> android.util.Log.i(tag, line) }

    /** Phrase [phrase] attendue sur la [voie] (décidée, pas encore jouée). */
    fun expect(voie: String, phrase: String) = sink(TAG, lineExpect(voie, phrase))

    /**
     * Lecture démarrée : [source] joue [detail] — [detail] est la phrase
     * jouée (le compteur l'apparie à l'EXPECT à la phrase près).
     */
    fun play(source: String, detail: String) = sink(TAG, linePlay(source, detail))

    /** Phrase [phrase] allée au bout (lecture terminée). */
    fun done(phrase: String) = sink(TAG, lineDone(phrase))

    /** Échec en [ou] (lieu) : [cause] (jet, coupure, synthèse…). */
    fun fail(ou: String, cause: String) = sink(TAG, lineFail(ou, cause))
}

/** Ligne EXPECT : `EXPECT voie=<voie> phrase=[<phrase>]`. Pure, testée en JVM. */
fun lineExpect(voie: String, phrase: String): String = "EXPECT voie=$voie phrase=[$phrase]"

/** Ligne PLAY : `PLAY source=<source> detail=[<detail>]`. Pure, testée en JVM. */
fun linePlay(source: String, detail: String): String = "PLAY source=$source detail=[$detail]"

/** Ligne DONE : `DONE phrase=[<phrase>]`. Pure, testée en JVM. */
fun lineDone(phrase: String): String = "DONE phrase=[$phrase]"

/** Ligne FAIL : `FAIL ou=<ou> cause=[<cause>]`. Pure, testée en JVM. */
fun lineFail(ou: String, cause: String): String = "FAIL ou=$ou cause=[$cause]"
