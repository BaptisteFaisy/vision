# Pack voix — mêmes phrases ElevenLabs, zéro latence

Le guidage (TwinLiteNet aujourd'hui, traversée et navigation demain) parle la
**même voix ElevenLabs** que la narration, lue par le **même lecteur partagé**
(`SpeechPlayer` de l'écran iSee : une seule lecture à la fois, chaque entrée
coupe la précédente). Fini les deux moteurs qui se parlent dessus.

## Principe

1. Les phrases fixes vivent dans `tools/voicepack-catalog.json` (miroir du
   Kotlin : `TwinLiteText`, `CrossingText`, `VoiceNavPolicy`).
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
ne colle pas à la voix réglée). `--force` régénère tout.

## Garde

`python3 tools/check-voicepack.py` (CI) : chaque texte existe dans le Kotlin,
chaque MP3 existe et pèse > 0, `vp_voice.txt` = `LAURE_VOICE_ID`.
`--allow-missing` pour les branches qui codent avant de générer.

## Ajouter une phrase

1. L'écrire dans le Kotlin d'abord (seule source parlée).
2. L'ajouter au catalogue (`parts` si composée en code).
3. Régénérer le pack, `check-voicepack.py`, commiter MP3 + catalogue.
