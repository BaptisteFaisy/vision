plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// ── Lint : une seule configuration, partagée par les modules Android.
//
// Mesuré le 26/10 sur le build complet : les tâches lint pèsent 91 s de temps
// tâche sur 386 s, soit 24 % — le poste le plus cher du budget de 5 min.
// Trois réglages, aucun contrôle retiré (le rapport de lint est identique
// avant/après : 0 erreur, 26 avertissements, 1 indication) :
//
//  1. `ignoreTestSources` : deux tâches d'analyse ne servaient à rien.
//     `checkTestSources` vaut `false` par défaut, donc lint ne remontait
//     jamais d'issue des sources de test — il les analysait quand même.
//     `:app:lintDebug` passe de 66 à 55 tâches, résultat inchangé.
//  2. `checkReleaseBuilds` : `:app:assembleRelease` relançait toute l'analyse
//     sur la variante release (`lintVital*`, 22 s mesurées) alors que
//     `:app:lintDebug`, dans le même workflow, analyse les mêmes sources avec
//     les mêmes règles (`abortOnError` vrai des deux côtés). Il n'existe
//     aucune source spécifique release dans ce dépôt : mêmes fichiers.
//     Coupé en CI seulement (`CI` est posé par GitHub Actions) : sur un poste,
//     `assembleRelease` garde son filet, comme avant.
//  3. Trois checks d'obsolescence de dépendances : ils ne protègent rien du
//     produit (ils disent « une version plus récente existe ») et interrogent
//     le réseau à chaque course.
//
// `abortOnError` reste à `true` (défaut) : une erreur de lint fait échouer le
// job, exactement comme avant.
val ci: Boolean = System.getenv("CI") != null

fun com.android.build.api.dsl.Lint.reglageCommun() {
    ignoreTestSources = true
    checkReleaseBuilds = !ci
    disable.addAll(listOf("GradleDependency", "AndroidGradlePluginVersion", "GradlePluginVersion"))
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> { lint { reglageCommun() } }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> { lint { reglageCommun() } }
    }
}
