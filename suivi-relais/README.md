# Relais de suivi — Worker + D1

Le relais que l'app utilise **par défaut** (`Settings.DEFAULT_PARENT_SITE_URL`).
Il remplace l'API du site Vercel `suivi-parent/`, abandonné : ce déploiement était
figé sur un commit du 27/10 et refusait toute écriture sans une clé que les
téléphones n'ont pas (403 « clé invalide »), donc la carte du proche restait vide
sans un mot.

## Contrat (inchangé pour la page embarquée dans l'APK)

```text
POST /api/share   {shareId, writeKey, fix:{lat,lon,accM,headingDeg,atMs,indoor},
                   objects:[{label,distM,angleDeg}], scene:{text,fromUser,speaking},
                   photo:{jpeg,atMs}, trip:{destination,totalM,remainingM,remainingS,progress}}
GET  /api/state?share=<identifiant>   → {ok, live, ageMs, fix, objects, scene, photo, trip, serverAt}
GET  /health                          → {ok:true}
```

- `photo` (JPEG base64 ≤ 120 000 caractères) et `trip` (progression 0..1) sont
  facultatifs : un appareil d'avant continue d'envoyer position, objets et
  phrase. Une écriture **sans** image (service de suivi écran éteint) garde la
  dernière photo et son `atMs` : la page la cache au lieu de la faire passer
  pour du direct. Un `trip` absent retire la barre de progression.

- **Un état par identifiant de flux** : deux proches suivis = deux `?share=`, deux
  lignes distinctes. Plusieurs caregivers peuvent lire le même identifiant, aucun
  ne peut écrire à la place d'un autre (`caregiverRole` le bloque côté app).
- `live` dure **12 s** ; au-delà la page passe en SIGNAL LOST puis OFFLINE en
  gardant la dernière position (`ageMs`, `serverAt`).
- **Aucune clé à saisir** : la clé d'écriture est liée au flux au premier envoi
  (`write_key` en base). Une clé différente ensuite → 403, ce qui empêche un
  identifiant deviné d'injecter une fausse position. L'app génère cette clé à
  l'installation (`defaultShareKey`), elle n'est jamais montrée ni partagée.
- `SHARE_WRITE_KEY` (secret du Worker) reste possible comme verrou global : s'il
  est défini, toutes les écritures doivent porter cette clé.

## Stockage

D1, table `streams (id, doc, updated_at, write_key)`, schéma créé à la première
requête. Un flux est conservé 24 h puis purgé (une écriture sur 200).

## Vérifier

```bash
node --test suivi-relais/test/relais.test.mjs
```

Les tests couvrent : isolement de deux flux, écriture sans clé, clé liée au flux
(403), verrou global optionnel, corps invalides, lecture sans identifiant, âge de
l'état et bornage du texte.

## Déployer

Le Worker est publié par le manifeste `deploy.json` (modules ESM + binding `DB`).
L'URL de production est recopiée dans `Settings.DEFAULT_PARENT_SITE_URL` côté
`:core` ; la changer impose un APK neuf.
