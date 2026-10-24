# app/ — SAM Vision (Expo + TypeScript)

App Android du projet sam3-vision : photo (caméra ou galerie) → `POST /analyze` → masques
superposés + objets + description → chat via `POST /ask`.

## Ce qui reste à brancher

- **URL du backend** : ✅ branchée le 2026-10-24 — `app.json` → `expo.extra.apiUrl` =
  `https://baptiste-faisy--sam3-vision-sam3service-web.us-east.modal.run`, APK reconstruit
  (prébuild + `assembleRelease`) et vérifié : l'URL est compilée dans `assets/app.config`
  de l'APK, et « Tester la connexion » renvoie *Backend joignable : sam3.1 sur cuda* sur
  emulator-5558. `expo.extra` étant compilé dans le bundle, toute change d'URL exige un
  rebuild (`scripts/build-apk.sh`), pas seulement un rechargement JS. L'URL reste
  modifiable à chaud dans Réglages (AsyncStorage).
- **Prebuild** : `app/android/` est généré par un autre agent. Si `app.json` a changé après
  le prebuild (package `com.baptiste.sam3vision`, permissions, plugins), relancer
  `npx expo prebuild --platform android --clean` avant de builder.

## Structure

| Fichier | Rôle |
| --- | --- |
| `App.tsx` | routage d'écrans (état local, pas de lib de navigation) |
| `src/api.ts` | **unique** client HTTP : fetch, timeout 60 s (AbortController), erreurs typées |
| `src/parse.ts` | validation runtime des 4 réponses du contrat (pur, testable hors RN) |
| `src/utils/config.ts` | URL backend : AsyncStorage + défaut `expo.extra.apiUrl` |
| `src/utils/image.ts` | redimensionnement ≤ 1024 px + JPEG 0.8 avant envoi |
| `src/utils/mask.ts` | PNG gris (255 = objet) → PNG RGBA colorisé à 45 % (upng-js) |
| `src/hooks/*` | `usePhotoCapture`, `useAnalysis`, `useChat`, `useApiUrlSettings`, `useBackendCheck` |
| `src/components/*` | `PhotoStage`, `ObjectList`, `DescriptionCard`, `ChatInput`, `ChatBubble`, `Feedback`, … |
| `src/screens/*` | `Camera`, `Result`, `Settings` |

## Décisions techniques

- **Masques** : le backend renvoie du PNG 8 bits *niveaux de gris sans canal alpha*, donc un
  `tintColor` React Native colorerait tout le rectangle. `src/utils/mask.ts` décode le PNG,
  reconstruit un RGBA `(couleur, alpha = gris × 0.45)` et le ré-encode (upng-js, pur JS).
  Les masques sont sous-échantillonnés à 512 px max pour limiter le coût CPU.
- **Photo bornée à 1024 px** : les masques ont la taille de l'image envoyée ; borner l'entrée
  évite des tableaux RGBA de plusieurs dizaines de Mo côté JS.
- **Pas de lib de navigation** : trois écrans, un état `Route` typé dans `App.tsx`.
- **Aucune clé API** dans l'app : tout passe par le backend.
- **Erreurs** : `timeout` (60 s), `network`, `http` (message `detail` du serveur), `invalid`
  (contrat non respecté) — chacune affichée avec un bouton Réessayer.

## Vérifications

```bash
npx tsc --noEmit          # 0 erreur
npx expo-doctor           # 20/20 checks passed
python3 tools/check-limits.py   # fichiers < 500 lignes, fonctions < 50 lignes
```

Tests hors React Native (Node, dans /tmp, non versionnés) : parsing des mocks `/segment`,
`/analyze`, `/ask`, `/health` + rejets des réponses invalides ; client `src/api.ts` contre un
serveur HTTP local (succès, HTTP 400 avec `detail`, JSON invalide, hôte injoignable) ;
colorisation d'un vrai PNG gris vérifiée pixel par pixel avec Pillow.
