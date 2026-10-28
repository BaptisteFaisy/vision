package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings

/**
 * Épinglage démo des modèles : le défaut des questions est Meta
 * ([Settings.DEFAULT_LLM_MODEL]), la narration du Direct garde son modèle rapide figé
 * ([Settings.DEFAULT_LIVE_LLM_MODEL], repli documenté dans `Settings`).
 *
 * Fonctions pures (aucun Android) : la garde anti-override (`seeded`), l'indicateur des
 * Réglages et la ligne du panneau debug partagent cette seule implémentation.
 */

/** Vrai si [model] est le modèle épinglé (celui de la démo, jamais réécrit). */
fun isPinnedLlmModel(model: String): Boolean =
    model.trim() == Settings.DEFAULT_LLM_MODEL

/**
 * Avertissement visible quand le **build** (`local.properties` → `BuildConfig`) fait
 * tourner l'app sur un autre modèle que l'épinglé : installation neuve + `llm.model`
 * renseigné avec une autre valeur, ou migration vers un modèle de build non épinglé.
 * `null` = rien à signaler (épinglé, ou choix saisi à la main dans Réglages).
 *
 * Un choix saisi à la main n'est jamais un override : il ne coïncide avec le modèle du
 * build que s'il est identique — et dans ce cas l'utilisateur l'a voulu.
 */
fun modelOverrideWarning(settings: Settings, buildModel: String): String? {
    val clean = buildModel.trim()
    if (clean.isBlank() || isPinnedLlmModel(clean)) return null
    if (settings.llmModel.trim() != clean) return null
    return "Modèle du build : $clean (épinglé démo : ${Settings.DEFAULT_LLM_MODEL})"
}

/**
 * Statut de la clé LLM, **chauffé par un appel réel** : seul un « Tester le LLM » qui a
 * répondu OK (« LLM OK — … » dans [testLine]) la déclare chauffée. Une clé renseignée
 * mais jamais testée le dit — elle ne prétend pas marcher.
 */
fun llmKeyStatus(settings: Settings, testLine: String?): String = when {
    settings.llmApiKey.isBlank() -> "clé LLM : absente"
    testLine?.startsWith("LLM OK") == true -> "clé LLM : chauffée (dernier test OK)"
    testLine?.startsWith("LLM KO") == true -> "clé LLM : en échec (dernier test KO)"
    else -> "clé LLM : renseignée, non testée (« Tester le LLM »)"
}

/** Statut de la clé de narration (pas de bouton de test : présence seule). */
fun liveKeyStatus(settings: Settings): String =
    if (settings.liveLlmConfigured) "clé narration : renseignée" else "clé narration : absente (Direct muet)"

/**
 * Lignes lues seules de l'indicateur « Modèles effectifs » des Réglages : noms exacts,
 * bases, statuts de clés — puis l'avertissement d'override du build, s'il y en a un.
 */
fun modelPinLines(settings: Settings, buildModel: String, testLine: String?): List<String> =
    buildList {
        add("questions : ${settings.llmModel} @ ${settings.llmBaseUrl}")
        add("direct : ${Settings.DEFAULT_LIVE_LLM_MODEL} @ ${Settings.DEFAULT_LIVE_LLM_BASE_URL}")
        add(llmKeyStatus(settings, testLine))
        add(liveKeyStatus(settings))
        modelOverrideWarning(settings, buildModel)?.let { add("⚠ $it") }
    }

/** Une seule ligne pour le panneau debug : les deux modèles + la clé de narration. */
fun debugModelsLine(settings: Settings): String =
    "llm : ${settings.llmModel} · live : ${Settings.DEFAULT_LIVE_LLM_MODEL} " +
        if (settings.liveLlmConfigured) "(clé OK)" else "(sans clé)"
