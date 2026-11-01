# Pack voix — mêmes phrases ElevenLabs, zéro latence

Le guidage (traversée et navigation) parle la
**même voix ElevenLabs** que la narration, lue par le **même lecteur partagé**
(`SpeechPlayer` de l'écran iSee : une seule lecture à la fois, chaque entrée
coupe la précédente). Fini les deux moteurs qui se parlent dessus.

## Principe

1. Les phrases fixes vivent dans `tools/voicepack-catalog.json` (miroir du
   Kotlin : `CrossingText`, `VoiceNavPolicy`) — y compris les **états de
   mouvement** (`vehicle_car_moving`, `cycle_velo_moving`… : depuis le
   01/11 un mobile ne dit plus sa position, donc sa phrase ne varie plus,
   donc elle est enregistrée — § 32 de `VOIX-DANGER.md`).
2. `tools/gen-voicepack.py` les synthétise **une fois** (voix Laure par défaut,
   mêmes paramètres que l'app) en `app/src/main/res/raw/vp_<id>_<lang>.mp3` +
   `assets/vp_voice.txt` (voix du pack).
3. Au runtime, `VoicePack` rejoue le clip quand la voix réglée est celle du
   pack ; sinon (voix changée, clip manquant, clé absente) : synthèse
   ElevenLabs bornée à 500 ms puis mise en cache, comme `AnnouncementPlayer`
   (jamais de TTS système, jamais de son en retard).

## Générer (détenteur de la clé)

```bash
ELEVENLABS_API_KEY=… python3 tools/gen-voicepack.py
git add app/src/main/res/raw/vp_*.mp3 app/src/main/assets/vp_voice.txt
```

La clé ne transite que par l'environnement (jamais affichée ni stockée).
Voix non défaut : `--voice <id>` (le pack reste ignoré tant que `vp_voice.txt`
ne colle pas à la voix réglée). `--regenerate` régénère tout.

## Garde

`python3 tools/check-voicepack.py` (CI) : chaque texte existe dans le Kotlin,
chaque MP3 existe et pèse > 0, `vp_voice.txt` = `LAURE_VOICE_ID`.
`--allow-missing` pour les branches qui codent avant de générer.

## Ce qui n'est jamais un clip

Les phrases qui portent une **mesure** ou un **compte** restent en synthèse :
arrêt et stationnement (« Voiture à l'arrêt, à 2 mètres. »), pluriel générique
(« 2 vehicles moving. »), positions (« Personne à 1 heure. »). `clipIdFor` rend
`null` pour elles — c'est la table qui décide, pas l'appelant
(`VehicleStateText.clipIdFor`, `CycleText.clipIdFor`, lues par
`recordedClipId`).

## Ajouter une phrase

1. L'écrire dans le Kotlin d'abord (seule source parlée).
2. L'ajouter au catalogue (`parts` si composée en code).
3. Régénérer le pack, `check-voicepack.py`, commiter MP3 + catalogue.
