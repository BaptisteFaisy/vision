# Suivi parent — page unique mobile-first (repo vision)

Une seule page `index.html` autonome, hébergeable en statique sur Vercel
(dossier racine Vercel : ce dossier, framework « Other », build vide).
Le relais live vit dans `api/` (fonctions serverless, sans aucune dépendance npm).

## Ce que la page affiche
- **Un seul bloc Carte** : les objets détectés par le téléphone + Léo (point bleu)
  et un halo bleu = imprécision GPS réelle en mètres. Plus de radar séparé ni de
  liste par catégorie : tout est posé directement sur la carte.
- **Zoom** : pincez, boutons +/− et boussole (MapLibre) ou contrôle natif Google ;
  bouton « Recentrer sur Léo » + recentrage auto.
- **Google Maps** si une clé est présente (`?key=TA_CLE` ou champ sous la carte,
  gardée dans le navigateur — jamais en dur), sinon repli gratuit OpenFreeMap/Esri.
- **En-tête** : badge `LIVE` (< 12 s) / `SIGNAL PERDU` (< 2 min) / `DÉMO` (aucun flux),
  âge du dernier état, et mention « position approximative (intérieur, ±X m) »
  quand le téléphone signale une précision dégradée.

## Flux live (câblé)
Téléphone (`sam-vision-master`, écran Direct) → `POST /api/share` toutes les 2 s :
`{shareId, writeKey, fix:{lat,lon,accM,headingDeg,atMs,indoor}, objects:[{label,distM,angleDeg}]}`
(`angleDeg` = cap absolu : cap téléphone + angle boîte/FOV, voir `SharePayload.kt`).
La page lit `GET /api/state?share=<id>` toutes les 2 s et place chaque objet en
polaire depuis la position GPS reçue (`destPoint`). Un identifiant de flux par
proche (`?share=`) : personne d'autre ne voit la carte d'un autre.

## Variables d'environnement Vercel
- `SHARE_WRITE_KEY` : si définie, `POST /api/share` exige la même clé (sinon 403).
  À saisir aussi dans l'app : Réglages → « Clé d'écriture du relais »
  (vide des deux côtés = relais ouvert).
- `UPSTASH_REDIS_REST_URL` + `UPSTASH_REDIS_REST_TOKEN` : stockage partagé
  (indispensable en prod : sans eux, le relais retombe sur une mémoire
  par instance serverless, donc l'état écrit n'est pas relu de façon fiable).
  Les noms de l'intégration Vercel KV fonctionnent aussi :
  `KV_REST_API_URL` / `KV_REST_API_TOKEN`.

## Déployer
```bash
npx vercel deploy --prod --yes
```
Dossier déployé : ce dossier (`suivi-parent/`), projet Vercel `suivi-parent`
→ https://suivi-parent.vercel.app/.

## Ancien contrat (remplacé)
`POST /api/share { t, lat, lon, capDeg, objets:[{id,label,cat,distM,angleDeg}] }`.
