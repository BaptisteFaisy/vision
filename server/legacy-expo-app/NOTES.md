# app/ — SAM Vision (Expo + TypeScript)

App Android du projet sam3-vision : photo (caméra ou galerie) → `POST /analyze` → masques
superposés + objets + description → chat via `POST /ask`.

## Ce qui reste à brancher

- **URL du backend** : ✅ branchée (relevé antérieur, hors de cet arbre) — `app.json` → `expo.extra.apiUrl` =
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
| `tools/test-client.mjs` | tests versionnés hors RN (parsing, `api.ts`, masques) + leurs helpers dans `tools/testlib/` |

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
npx tsc --noEmit                # 0 erreur
npx expo-doctor                 # 20/20 checks passed
python3 tools/check-limits.py   # tout le dépôt : fichiers < 500 lignes, fonctions < 50 lignes
node tools/test-client.mjs      # tests client versionnés, sort ≠ 0 si un test échoue
```

Les tests du client sont **versionnés** dans `app/tools/test-client.mjs` (+ `app/tools/testlib/`) —
plus de tests « dans /tmp » :

- `parse.ts` : les 4 réponses du contrat, plus le rejet des formes invalides (champ manquant,
  mauvais type, `box` de 3 valeurs, `tags` non-liste…).
- `api.ts` contre un serveur HTTP local factice : succès des 4 routes, `POST`/`GET` et corps
  envoyés, HTTP 400 avec `detail`, corps non-JSON, JSON invalide, contrat non respecté, hôte
  injoignable, requête abandonnée (→ `timeout`).
- `utils/mask.ts` : colorisation d'un vrai PNG 8 bits gris vérifiée **pixel à pixel** (couleur
  exacte, `alpha = gris × 0,45`, opacité 1, `#rgb`, réduction à `MAX_MASK_EDGE` = 512 px).

Le runner compile `parse.ts` / `api.ts` / `utils/mask.ts` avec le `tsc` local dans
`app/.test-build/` (gitignoré), puis exécute les suites en CommonJS : rien à installer, aucun
émulateur. Constat au passage : `upng-js` 2.1.0 tronque le chunk `IEND` pour les images de 1-2 px
(données IDAT intactes) — sans effet réel, les masques font la taille de la photo ; le décodeur
de test tolère l'absence d'`IEND`.
