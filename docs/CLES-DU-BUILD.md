# Clés embarquées dans l'APK

Objectif (demande du 2026-10-26) : **installer l'APK suffit**. Personne ne doit ressaisir
de clé pour que le chat réponde et que la voix parle ; les clés sont dans le binaire.

## D'où elles viennent

| Réglage | `local.properties` (gitignoré) | `BuildConfig` |
| --- | --- | --- |
| Base URL LLM | `llm.base.url` | `LLM_BASE_URL` |
| Clé LLM | `llm.api.key` | `LLM_API_KEY` |
| Modèle LLM | `llm.model` | `LLM_MODEL` |
| Clé narration Direct | `llm.live.api.key` | `LIVE_LLM_API_KEY` |
| Clé ElevenLabs | `tts.api.key` | `TTS_API_KEY` |
| Voix ElevenLabs | `tts.voice.id` | `TTS_VOICE_ID` |
| Clé dictée Meta | `asr.api.key` | `ASR_API_KEY` |
| Endpoint SAM | `sam.base.url`, `sam.us.base.url` | `SAM_BASE_URL`, `SAM_US_BASE_URL` |
| URL routeur Valhalla | `route.base.url` (défaut : backend Modal CPU) | `ROUTE_BASE_URL` |
| Clé Google Maps | `google.maps.api.key` | `GOOGLE_MAPS_API_KEY` |
| Endpoint tap SAM 3.1 | `sam31.endpoint` (vide = tap désactivé) | `SAM31_ENDPOINT` |
| Surimpression locale (défaut build) | `live.local.overlay` | `LIVE_LOCAL_OVERLAY` |
| Dev lunettes (manifest) | `mwdat.application.id`, `mwdat.client.token` (défaut `"0"`) | — (`manifestPlaceholders`) |

`app/build.gradle.kts` lit le fichier, `app/SettingsSeed.kt` remplit les champs vides au
démarrage (`seededFromBuild`, utilisé par iSee, le Direct et l'écran photo). L'intro et
les endpoints SAM par région ont déjà leur défaut en dur : sans aucune clé, l'app
s'ouvre, parle et segmente ; seuls le LLM et la voix demandent une clé.

## Priorité

1. Une valeur **enregistrée dans Réglages** sur ce téléphone gagne toujours.
2. Sinon, la valeur du build remplit le champ vide.

Donc : une installation neuve hérite de tout ; quelqu'un qui a déjà saisi sa propre clé
la garde. Pour repartir du build, vider le champ et enregistrer.

Exception : une base inutilisable (vide ou sans `https://`, LLM comme SAM, route
ou dictée) n'est pas un choix — le build (à défaut le défaut du code, le preset
de région, Maps ou Scribe selon le champ) reprend la main tout seul.

## Modèles épinglés (démo)

| Rôle | Base | Modèle |
| --- | --- | --- |
| Questions / description | `https://inference.baseten.co/v1` | `meta-models/muse-spark-open-1026` (Meta) |
| Narration du Direct | `https://inference.baseten.co/v1` | `meta-models/muse-spark-open-1026` (Meta) |

Questions et narration parlent au même fournisseur (Baseten) et au même modèle Meta :
le raisonnement interne est coupé côté client (`enable_thinking=false`), donc le budget
de 200 tokens de la narration va à la phrase. Seule la clé de narration est injectable
(`llm.live.api.key`, une clé Baseten) ; base et modèle du Direct sont figés dans `Settings`.

Gardes : un `llm.model` de build différent de l'épinglé affiche un avertissement
visible dans Réglages (« Modèles effectifs ») au lieu de s'appliquer en silence ;
`migrateLegacyLlm` ne réécrit jamais un modèle déjà épinglé ; une mise à jour depuis
l'ancien défaut (InferHub + qwen) migre base, modèle **et** clé ensemble (sinon la
clé InferHub partirait chez Baseten et répondrait 401). Les Réglages montrent les
noms exacts, les bases et le statut des clés — la clé LLM n'est « chauffée »
qu'après un « Tester le LLM » qui a réellement répondu.

## APK publié par la CI

Le runner fait un checkout neuf : il n'a pas de `local.properties`, et un APK
bâti sans l'étape « Clés du build » part donc **sans clé**. Cette étape (action
composite unique `.github/actions/cles-build`) écrit ce fichier depuis les
secrets du dépôt quand ils existent (sinon elle ne fait rien) ; elle est
présente dans les jobs qui bâtissent (`ci.yml` : tests `:core` et `:app`, `ci-native.yml` :
`apk` debug+release), donc **chaque APK téléchargé depuis GitHub
embarque les sept clés secrètes** (les autres lignes — Maps, tap, overlay,
dev lunettes — viennent du `local.properties` du poste qui bâtit, ou restent
à leur défaut).

## Garde : prouvé à chaque build

`tools/check-apk-keys.py` tourne après chaque assemblage (debug puis release,
dans le job `apk`) et fait échouer le job si, les secrets existant : le
`local.properties` ne reprend pas la clé du secret, la clé est absente du dex
de l'APK, ou un ping minimal au LLM ne répond pas 200 (secret périmé ou
mauvais fournisseur — la panne vue avec des secrets restés sur InferHub alors
que le produit parlait à Baseten). Sans secret, la garde reste inactive et
l'APK sort sans clé, comme avant — mais elle émet un avertissement
(`::warning::`, visible dans le résumé du job) au lieu de se taire : un build
sans clé est un choix, pas un oubli. Ses tests (`tools/test_check_apk_keys.py`,
faux APK + faux LLM local, aucun réseau) tournent dans le job `ci`.

Secrets à créer — Settings → Secrets and variables → Actions → *New repository secret* :
`LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_LIVE_API_KEY`, `TTS_API_KEY`,
`TTS_VOICE_ID` (facultatif), `ASR_API_KEY` (facultatif). Aucune valeur n'est jamais
affichée par la CI.

Tous les secrets CI (31/10) :

| Secret | Consommé par | Effet si absent |
| --- | --- | --- |
| `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` | `cles-build` → `local.properties` → `BuildConfig` | APK sans LLM (saisie Réglages) ; garde inactive + avertissement |
| `LLM_LIVE_API_KEY` | idem (`LIVE_LLM_API_KEY`) | narration du Direct coupée (repli § « Clé narration vide ») |
| `TTS_API_KEY`, `TTS_VOICE_ID` | idem | voix ElevenLabs absente (repli TTS système) |
| `ASR_API_KEY` | idem | dictée Meta absente (repli Scribe) |
| `GOOGLE_MAPS_API_KEY` | `app/build.gradle.kts` (env ou `local.properties`) | clé Maps vide |
| `MODAL_TOKEN_ID`, `MODAL_TOKEN_SECRET` | `deploy-live-fast.yml` (auth Modal) | pas de déploiement GPU |

## Build sans clé : erreur ou avertissement (31/10)

`app/build.gradle.kts` refuse un trio LLM **partiel** (1-2 clés sur 3 dans
`local.properties`) : erreur claire à la configuration, pas de `BuildConfig`
silencieusement inutilisable. Trio entièrement vide = APK sans clé assumé
(saisie dans Réglages), avec un avertissement Gradle ; `SAM_REQUIRE_KEYS=1`
transforme ce cas en erreur (le message renvoie ici).

## Clé narration vide : le Direct se tait (31/10)

`LIVE_LLM_API_KEY` vide (ni build, ni Réglages : `liveLlmConfigured == false`)
coupe la narration du Direct — « Direct narration key (empty = muted
narration) ». Questions, description photo et voix inchangées : seule la
phrase du Direct manque. Le repli **runtime** (file d'attente, nouvelle
demande quand la clé arrive) est un autre agent ; ici, seul le contrat est
documenté : vide = muet, jamais d'erreur, jamais de clé d'un autre rôle
réutilisée en douce.

## Trafic en clair : debug seulement (31/10)

`network_security.xml` du `main` interdit le clair partout (release = HTTPS
obligatoire) ; l'exception vers les bacs d'essai locaux (10.0.2.2, localhost,
127.0.0.1) vit dans `src/debug/res/xml/network_security.xml`, qui recouvre le
`main` en build debug. Vérifié : le manifeste référence `@xml/network_security`
et seul le source-set debug porte un `domain-config` en clair — une URL
`http://` en release est refusée par le système, pas par le code.

## Avertissement

Une clé embarquée est **extractible** de l'APK par quiconque le possède : ne distribuer
l'APK qu'aux personnes à qui on accepte de prêter ces clés, et ne jamais le publier
(Play Store, dépôt public, lien ouvert). Le dépôt, lui, reste sans secret : garde CI
`git grep` + `local.properties` jamais versionné.
