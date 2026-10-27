# OPTIMISATIONS — ce qui a été refait, et mesuré

> Point de départ du 26/10/2026 : un commit unique, puis **17 missions parallèles** ont repris le
> code.
> Chaque chiffre ici est mesuré **dans ce dépôt** ; ce qui vient d'une mesure sur appareil
> est daté et renvoie à `docs/PERF.md`.

## Le point de départ, et l'écart

| | Avant | Après |
|---|---:|---:|
| Fichiers `.kt` | 282 | **364** |
| Lignes Kotlin | 29 803 | **37 755** |
| Tests JVM (`@Test`) | 637 | **1 011** (+374) |
| `@Preview` (relire un écran sans téléphone) | **0** | **11** |
| `Request.Builder` dans `core/net` | 10 fichiers / 15 sites | **1 fichier / 1 site** |
| Écran iSee réellement affiché | **non** (2 198 lignes de code mort) | **oui** |
| Jobs de CI | 1 (dépassait les 5 min) | **5, en parallèle** |
| ViewModels au-dessus de 420 lignes | **3** | **0** |

## 1. L'écran iSee branché — le point mort n°1

Le design iSee (bulle centrale, satellites, gros texte paginé, dissolution en damier de la
carte caméra) pesait **2 198 lignes** et **n'était appelé nulle part** : `grep -rn IseeScenePane`
ne renvoyait que sa définition. L'écran n'existait donc pas pour l'utilisateur.

- L'accueil monte maintenant la scène (`IhearHome` → `IseeScenePane`).
- Le doublon a été tranché : la liste de bulles (`IhearChatPane`, 418 l.) et l'ancienne
  fenêtre caméra (`IhearCameraWindow`, 296 l.) sont **supprimées**, pas masquées ; leurs
  contenus utiles (liaison caméra, pastilles de distance, panneau de debug) sont rapatriés
  sur la carte du design.
- Bilan : **+307 / −739 lignes**, 11 tests JVM sur le mapping état → scène.

## 2. Des previews pour relire l'écran sans téléphone

0 → **11 `@Preview`** : scène vide, message utilisateur (texte bleu), scène iSee, états
mémoire / écoute / voix, carte caméra avant-après photo, dissolution à t = 0 / 0,5 / 1,
planche des 16 apparences de bulle. Plus 12 tests de rendu JVM (plan de dissolution
26 × 16 = 416 cellules, déterminisme, pagination 19 caractères × 3 lignes avec recouvrement).

## 3. La garde de taille était aveugle

`tools/check-kotlin-limits.py` ne voyait pas les fonctions déclarées **entre backticks**
(≈ 83 fonctions, ~31 % du code) et acceptait une fonction de 50 lignes (`> 50` au lieu de
`>= 50`). Réécrite : parseur Kotlin (suspend, corps d'expression, accesseurs, classes
imbriquées), seuil `>= 50`, diagnostic `chemin:ligne`. **28 tests**.

## 4. Une seule fabrique de requêtes HTTP

15 `Request.Builder` dispersés dans 10 fichiers → **1** (`Http.kt`). Mêmes méthodes, URLs,
en-têtes, timeouts, User-Agents ; 10 tests sur la fabrique. **+82 / −94** lignes.

## 5. Le budget du chemin frame (mode Direct)

Banc JVM (chauffe 50 itérations, 200 mesurées) ; le chemin frame **n'alloue plus par frame**,
la réserve est **bornée à une** et sûre entre threads. La règle produit tient : une seule
frame en vol, la suivante est **jetée** (pas mise en file).

## 6. Masques : un bug de produit, et −52 % de décodage

`decodeBitsMask` acceptait un corps M1 **trop long en le tronquant en silence** (masque faux
au lieu d'un `null`), alors que le backend exige `bits.size == ceil(w/8) * h`. Le décodage est
borné (8 Mio), l'en-tête est départagé **par la longueur du corps**, et un en-tête
`65535 × 65535` est refusé **avant toute allocation** (184 octets lus).

| | Avant | Après |
|---|---:|---:|
| Décodage M1 640 × 360 | 350 µs | **169 µs (−52 %)** |
| Coloriage LUT / masque | — | 81–135 µs (0,35–0,59 ns/px) |
| Allocation par coloriage | — | **0 octet** (sonde étalonnée sur un bloc qui alloue) |

## 7. Trois ViewModels découpés

| Fichier | Avant | Après | Tests ajoutés |
|---|---:|---:|---:|
| `LiveViewModel.kt` (+ `app/live/`) | 490 l. | **411 l.** (391, puis +20 pour le budget frame) | 41 |
| `IhearViewModel.kt` (+ `app/ihear/vm/`) | 469 l. | **327 l.** | oui |
| `AppViewModel.kt` (+ `app/vm/`) | 417 l. | **276 l.** | oui |

Comportement public **inchangé** (listes de membres publiées avant/après dans chaque PR),
plus longue fonction ramenée sous 50 lignes.

## 8. Le contrat HTTP, verrouillé par 93 tests

`:core` 468 → **561 tests** : les 10 clients sont couverts hors ligne (succès, 400, 401, 402,
429, 500, corps vide, JSON tronqué, timeout, en-têtes, User-Agent). La campagne a **figé
5 défauts réels**, puis une mission les a **corrigés** :

1. `POST /release` avalait les refus (401/402/429 → `released=false`) et **rejouait** la
   requête : une requête désormais, et une erreur typée.
2. `/live/ws` ne mappait pas `{"error":{"status":402}}` sur `PaymentRequired`, contrairement
   à `POST /live`.
3. TTS : un corps 200 **texte** (« Bad Gateway ») passait pour de l'audio.
4. STT / Meta ASR : une page HTML d'erreur devenait la **dictée affichée**.
5. Vidéo : un JSON d'erreur en 200 s'affichait comme une réponse.

## 9. Les réglages : trois fuites corrigées

- `Settings.toString()` (data class) imprimait **les trois clés API en clair** — tout log,
  toute exception les recrachait. Masquées, avec test.
- Le repli chiffré AES-GCM écrivait **en place, au mode 0644** : écriture atomique
  (`*.tmp` + renommage) et **0600 forcé**.
- `EncryptedSettingsStore` ne persistait **ni** `distanceOverlayEnabled` **ni**
  `cameraFovDeg` : deux réglages perdus au redémarrage. Corrigés, parité garantie par test.

## 10. `stop()` du direct arrête vraiment

Deux défauts réels : une frame en vol pouvait **réécrire l'état après l'arrêt**, et le
journal / l'historique n'étaient vidés qu'au `start()` suivant. Les deux sont corrigés
(frame « périmée » à l'arrêt, journal vidé), avec la preuve rouge → vert dans la PR.

## 11. La CI tient les 5 minutes

Un seul job enchaînait tests + 2 APK + lint + le module lunettes : **coupé au budget** (mesuré).
Le dépôt public donne **une VM par job**, d'où des jobs parallèles, chacun borné à 5 minutes,
cache Gradle compris :

| Job | Durée mesurée |
|---|---:|
| `ci` (tests JVM, le check exigé) | **33 s** |
| `lint` | ~2 min |
| `apk` / `apk-release` | ~1 min 40 / 2 min 34 |

Le job du module lunettes (143 s mesurés) a disparu avec le module : le banc vit maintenant dans
`:app` et c'est le job `ci` qui exécute ses tests.

Lint ensuite allégé **sans retirer de contrôle** : `ignoreTestSources` (lint n'analysait des
tests pour rien), `checkReleaseBuilds` coupé **en CI seulement** (`assembleRelease` relançait
22 s d'analyse sur les mêmes sources) — `abortOnError` reste vrai. Périmètre « code » élargi :
tout fichier sous un module compte, pas seulement les `.kt` (un `AndroidManifest.xml` ou un
schéma Room modifié passait pour « sans code » ⇒ **vert par absence de test**).

**Refusé après mesure** : `org.gradle.configuration-cache` — il casse le build, parce que
`app/build.gradle.kts` appelle `git` (versionCode/versionName) au moment de la configuration.
C'est documenté dans `gradle.properties` pour ne pas le retenter par hasard.

## 12. Un bug de CI qui n'était pas un bug de CI

`formatLiveStatus` formatait « %.1f » **sans locale** : « 1,4 ips » sur un poste français,
« 1.4 ips » sur le runner GitHub. La CI tombait sur `LiveStateTest` alors que le test passait
chez l'auteur. Corrigé (`Locale.FRANCE`) et **verrouillé** par un test qui joue quatre locales.

## 13. Le reste du dépôt traité dans la même passe

- **`scripts/`** : `set -euo pipefail`, variables citées, `mktemp` + `trap`, une seule
  suppression récursive (`safe_rm_rf` refuse un chemin vide, `..` ou hors `TMPDIR`), racine du
  dépôt déduite de `BASH_SOURCE`, dépendances annoncées, `--help` en 0 / argument inconnu en 2.
  **53 assertions** de test et un inventaire de ce que chaque script lit comme secret.
- **Le banc lunettes, aujourd'hui dans `:app`** : `GlassesSession` (83 l.) découpé en session /
  runtime (`GlassesSessionRuntime.kt`, 107 l.) / phases (`GlassesRegistrationPhase.kt`, 69 l.) /
  politique pure (`GlassesSessionPolicy.kt`, 226 l.), et **84** tests JVM sur la partie pure —
  sans stubber le SDK Meta (`SessionPolicyTest` importe ses types, il n'en instancie aucun).
- **Documentation** : `README.md` (qui annonçait un dossier `backend/` inexistant ici),
  `docs/ARCHITECTURE.md`, `docs/PERF.md`, `docs/DOC-ETAT.md` — chaque affirmation relue dans
  le code de cet arbre.

## Ce qui n'est **pas** prouvé ici

Le build compile, les tests passent, les previews existent — mais **rien n'a été regardé sur
un écran dans cette passe**. La recette iSee (swipe gauche/droite du gros texte, texte bleu
pendant la dictée, dissolution au double tap, monde persistant) reste à jouer sur l'appareil :
c'est la seule validation qui vaille pour un écran.