package dev.sam3vision.app.cleanup

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import androidx.room.DatabaseConfiguration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import dev.sam3vision.app.media.MediaDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Non-régression JVM du crash Room au démarrage : un téléphone qui porte une base
 * plus récente que le schéma de l'app ne doit plus crasher au downgrade
 * (`IllegalStateException: A migration from 2 to 1…`, vu 9 fois sur l'appareil
 * le 27/10) — la base est recréée vide.
 *
 * La base n'est jamais ouverte (pas de SQLite natif en JVM) : chaque test construit
 * via le chemin de production (`CleanupDatabase.build`), extrait le rappel réel
 * (délégué généré par KSP inclus) et rejoue `onDowngrade(2, 1)` dessus avec une
 * fausse base. Le témoin [sansRepli_downgrade_2_vers_1_rejoueLeCrash] prouve la
 * fidélité du montage : sans le repli, le même chemin rejoue le message exact du
 * crash. Tourne dans le job `ci` (`:app:testDebugUnitTest`).
 */
class CleanupDowngradeTest {

    @Test
    fun cablage_poseLeRepliDestructifAuDowngrade() {
        val db = CleanupDatabase.build(fauxContexte(), "test-cablage.db")
        try {
            val configuration = configurationDe(db)
            assertTrue("repli destructif au downgrade attendu", configuration.allowDestructiveMigrationOnDowngrade)
            assertTrue("les montées sans migration restent des crashs volontaires", configuration.requireMigration)
        } finally {
            runCatching { db.close() }
        }
    }

    @Test
    fun downgrade_2_vers_1_recreeSansCrasher() {
        val db = CleanupDatabase.build(fauxContexte(), "test-downgrade.db")
        try {
            val fausse = FausseBase()
            rappelDe(db).onDowngrade(fausse.base, 2, 1) // ne doit pas lever
            assertTrue("DROP attendu : ${fausse.ordresSql}", fausse.ordresSql.any { estDropTraces(it) })
            assertTrue("CREATE attendu : ${fausse.ordresSql}", fausse.ordresSql.any { estCreateTraces(it) })
        } finally {
            runCatching { db.close() }
        }
    }

    @Test
    fun sansRepli_downgrade_2_vers_1_rejoueLeCrash() {
        // Témoin négatif : sans le repli, le même chemin rejoue le crash vu sur
        // l'appareil — preuve que le test ci-dessus exerce bien le chemin du crash.
        val db = Room.databaseBuilder(fauxContexte(), CleanupDatabase::class.java, "test-sans-repli.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try {
            try {
                rappelDe(db).onDowngrade(FausseBase().base, 2, 1)
                fail("sans repli, le downgrade 2 → 1 doit lever IllegalStateException")
            } catch (attendu: IllegalStateException) {
                assertTrue(
                    "message inattendu : ${attendu.message}",
                    attendu.message!!.startsWith("A migration from 2 to 1 was required but not found"),
                )
            }
        } finally {
            runCatching { db.close() }
        }
    }

    @Test
    fun mediaDatabase_poseLeRepliDestructifAuDowngrade() {
        // Audit : l'autre base de l'app garde son repli (partagé depuis ce correctif).
        val db = MediaDatabase.build(fauxContexte(), "test-audit-media.db")
        try {
            assertTrue(configurationDe(db).allowDestructiveMigrationOnDowngrade)
        } finally {
            runCatching { db.close() }
        }
    }

    private fun estDropTraces(sql: String): Boolean = sql.startsWith("DROP TABLE") && "traced_captures" in sql

    private fun estCreateTraces(sql: String): Boolean =
        sql.startsWith("CREATE TABLE") && "traced_captures" in sql

    /**
     * Contexte factice : Room le stocke sans jamais l'appeler (le journal est fixé
     * à WAL, donc `JournalMode.resolve` ne touche pas au Context ; la base n'est
     * jamais ouverte). Aucune méthode Android n'est invoquée.
     */
    private fun fauxContexte(): Context = ContextWrapper(null)

    /** Le rappel SQLite réel (délégué généré inclus) caché dans le wrapper d'ouverture. */
    private fun rappelDe(db: RoomDatabase): SupportSQLiteOpenHelper.Callback =
        lireChamp(db.openHelper, "callback")

    /** La configuration réelle : rappel → gestionnaire de connexion → configuration. */
    private fun configurationDe(db: RoomDatabase): DatabaseConfiguration {
        val gestionnaire = lireChampParNomDeType(rappelDe(db), "androidx.room.RoomConnectionManager")
            ?: throw AssertionError("gestionnaire de connexion introuvable (Room 2.8.5 attendu)")
        return lireChamp(gestionnaire, "configuration")
    }

    /** Lit un champ privé en remontant les super-classes (noms vérifiés sur Room 2.8.5). */
    private fun <T> lireChamp(cible: Any, nom: String): T {
        var classe: Class<*>? = cible.javaClass
        while (classe != null) {
            val champ = classe.declaredFields.firstOrNull { it.name == nom }
            if (champ != null) {
                champ.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return champ.get(cible) as T
            }
            classe = classe.superclass
        }
        throw AssertionError("champ $nom introuvable dans ${cible.javaClass.name} (Room 2.8.5 attendu)")
    }

    /**
     * Premier champ du type nommé (la classe est interne à Room, donc non
     * référençable ; évite aussi les noms synthétiques comme `this$0`).
     */
    private fun lireChampParNomDeType(cible: Any, nomType: String): Any? {
        var classe: Class<*>? = cible.javaClass
        while (classe != null) {
            val champ = classe.declaredFields.firstOrNull { it.type.name == nomType }
            if (champ != null) {
                champ.isAccessible = true
                return champ.get(cible)
            }
            classe = classe.superclass
        }
        return null
    }

    /**
     * Fausse base SQLite : Room y joue le downgrade (requêtes, DROP, CREATE) sans
     * SQLite natif. Les `query` rendent un curseur vide (aucune table/vue à énumérer) ;
     * les `execSQL` et `compileStatement` sont enregistrés pour prouver la recréation.
     */
    private class FausseBase {
        val ordresSql = mutableListOf<String>()

        private val curseurVide: Cursor =
            Proxy.newProxyInstance(
                FausseBase::class.java.classLoader,
                arrayOf<Class<*>>(Cursor::class.java),
            ) { _, methode, _ ->
                when (methode.name) {
                    "moveToFirst", "moveToNext" -> false
                    "getCount", "getPosition" -> 0
                    "getColumnName", "getString" -> ""
                    "toString" -> "CurseurVide"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> valeurDefaut(methode.returnType)
                }
            } as Cursor

        private val fausseCommande: SupportSQLiteStatement =
            Proxy.newProxyInstance(
                FausseBase::class.java.classLoader,
                arrayOf<Class<*>>(SupportSQLiteStatement::class.java),
            ) { _, methode, _ ->
                when (methode.name) {
                    "simpleQueryForString", "getColumnName" -> ""
                    "toString" -> "FausseCommande"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> valeurDefaut(methode.returnType)
                }
            } as SupportSQLiteStatement

        val base: SupportSQLiteDatabase =
            Proxy.newProxyInstance(
                FausseBase::class.java.classLoader,
                arrayOf<Class<*>>(SupportSQLiteDatabase::class.java),
            ) { _, methode, args ->
                when (methode.name) {
                    "isOpen", "isDatabaseIntegrityOk" -> true
                    "getAttachedDbs" -> emptyList<Pair<String, String>>()
                    "query" -> curseurVide
                    "compileStatement" -> {
                        ordresSql += args?.get(0) as String
                        fausseCommande
                    }
                    "execSQL" -> {
                        ordresSql += args?.get(0) as String
                        null
                    }
                    "toString" -> "FausseBase(${ordresSql.size} ordres)"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> valeurDefaut(methode.returnType)
                }
            } as SupportSQLiteDatabase

        private fun valeurDefaut(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> null // void + tous les objets
        }
    }
}
