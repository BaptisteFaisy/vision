# Voix en anglais — la personne qui parle, pas l'interface

Demande user : « faire en sorte que **la personne nous parle en anglais** ». L'app s'appelle
**iSee**, le module de code est `app/ihear/`. Réglage `speechLanguage` (anglais par défaut),
deux boutons pleine largeur dans Réglages → section « Voix ElevenLabs (lecture à voix haute) »
→ **Langue de la voix** (`TtsSettings.kt:36`, `VoiceLanguageSettings.kt:24-35`).

## Ce que la langue change, et ce qu'elle ne change pas

| Suivant la langue de la voix | Reste en français |
|---|---|
| annonces des objets et de leur distance (`PositionText`, `DistanceText.spoken`) | toute l'interface (Réglages, panneau de debug, bandeaux) |
| réponses du chat, posées puis **lues telles quelles** (`LlmPrompts`) | libellés du direct et du tap |
| narration continue du direct (`LlmPrompts.liveUser`) | |
| journal caméra joint comme contexte d'une question de mémoire (`TimelineText.context`) | |
| phrase du bouton « Tester la voix » (`testVoiceText`) | |

L'intro embarquée (`res/raw/intro_ihear.mp3`, « Welcome to iSee… », voix Jade US) était
**déjà** en anglais :
c'est elle qui a donné le sens de la demande, tout le reste la rejoint.

## Où vit la langue

`core/speech/SpeechLanguage.kt:13-41` — deux valeurs (`Fr`, `En`), `DEFAULT = En` (`:32`),
lecture tolérante (`fromName` `:35-36`, `fromCode` `:39-40` : valeur inconnue ou absente ⇒
anglais). Persistée par les deux stores : `speech_language` (EncryptedSharedPreferences) et
`speechLanguage` (fichier privé, codec `clé=valeur`) — voir `RefreshSettings`/`SettingsStore`.

Une mise à jour depuis une version qui ne connaissait pas la clé retombe sur **anglais** :
c'est le comportement demandé, pas une surprise de migration.

### Les mots, par langue

Le cadran est le même (sept bandes égales, `clockHours`, `PositionText.kt:52`), seuls les mots changent :

| Heure | fr | en |
|---|---|---|
| 9 | à 9 heures | at 9 o'clock |
| 10 | à 10 heures | at 10 o'clock |
| 11 | à 11 heures | at 11 o'clock |
| 12 | à midi | at 12 o'clock |
| 1 | à 1 heure | at 1 o'clock |
| 2 | à 2 heures | at 2 o'clock |
| 3 | à 3 heures | at 3 o'clock |
| distance prononcée | 1,4 mètre · 85 centimètres · 12 mètres | 1.4 meters · 85 centimeters · 12 meters |
| objets non cités | et 2 autres objets | and 2 other objects |

Forme complète produite en anglais :
`Person at 9 o'clock, 1.4 meters away; chair at 12 o'clock, 3 meters away.`

Deux points qui ont leur raison :

- **la virgule décimale suit la langue** : `Locale.FRANCE` en français, `Locale.US` en
  anglais — la voix lit le texte tel quel, un « 1,4 meters » se prononcerait mal ;
- **la signature de scène reste française** (`PositionText.key`) : elle sert à ne pas
  répéter la même annonce. Si elle suivait la langue, basculer de langue ferait
  rebavarder toute la scène.

## Ce qui part au modèle

`core/net/LlmPrompts.kt` remplace les quatre consignes codées en dur. Une consigne par
langue, **mêmes clés JSON** (`description`, `tags`) pour que `parseAnalysis` continue de
lire la réponse. La consigne anglaise ajoute « for a blind user », ce que fait l'app.

Vérifié une fois sur le compte réel (`local.properties`, non rejoué ici) : la voix Jade US
synthétise une phrase anglaise en `eleven_v4_turbo` → 200, 54 795 octets de MP3. Le LLM n'a
pas pu être interrogé en vrai (429 `capacity_saturated` sur `ali/qwen3.8-omni-flash`) : que
la requête emporte bien la consigne anglaise est vérifié en JVM contre un faux serveur
(`LlmRequestLanguageTest`, 3 tests).

## Ce que la langue ne touche PAS

La **dictée** (Micro Meta / ElevenLabs Scribe) retranscrit ce que l'utilisateur dit, dans
la langue où il le parle : rien à régler. Les concepts SAM restent anglais (`person`,
`chair`, `table`…) — ils l'étaient déjà, et c'est ce qui rend la phrase anglaise lisible
sans rien traduire. Les distances affichées à l'écran gardent le format français
(`≈ 3,2 m`) : c'est de l'écran, pas de la voix.

## Recette

1. Réglages → « Langue de la voix » → deux boutons, **Voix en anglais (en)** actif par défaut.
2. « Tester la voix » dit « This is the SAM Vision voice. »
3. Repasser en « Voix en français (fr) » → la même phrase dit « Test de voix SAM Vision. »,
   et les annonces redisent « Personne à 9 heures, à 1,4 mètre. »
4. Enregistrer ferme les Réglages (`onSave = { save; pop() }`) : y revenir pour tester.
