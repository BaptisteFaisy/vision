# Micro de l'écran verrouillé

**Ce qui marchait mal** : appui long → le micro s'ouvre ; l'écran se verrouille → plus rien.
`MainActivity.onPause()` annule la tenue (`root.cancelGesture()` → `isee.onHoldCancel()` →
`dictation.discard()`), et de toute façon Android ne laisse pas une app en arrière-plan garder le
micro. Ce n'était donc ni un réglage ni un bug de geste : il manquait un **service de premier
plan** dans l'app.

## Ce que fait la branche `feat/micro-verrou`

| Pièce | Fichier | Rôle |
|---|---|---|
| Règles pures | `core/speech/LockedMic.kt` | 6 phases (`OFF`, `READY`, `LISTENING`, `THINKING`, `ANSWERED`, `FAILED`), plafond `MAX_RECORD_MS = 20 s`, textes affichés **et** prononcés |
| Question sans image | `core/net/Clients.kt` (`askText`), `InferHubLlmClient`, `LlmPrompts.askVoiceSystem` | écran verrouillé : la caméra est fermée, la consigne dit au modèle qu'il n'a **aucune** image |
| Trajet complet | `app/locked/LockedMicAnswer.kt` | PCM → texte (Meta/Scribe) → réponse → voix (ElevenLabs) |
| Service | `app/locked/LockedMicService.kt` | premier plan type `microphone`, réveil partiel **pendant la capture seulement** |
| Interface | `app/locked/LockedMicNotifications.kt`, `LockedMicTile.kt` | notification publique + tuile des réglages rapides |
| Gestes | `app/MainActivity.kt` | `onResume` arme, `isFinishing` désarme |

## La seule séquence légale (et pourquoi)

1. **iSee visible** → `LockedMicBridge.arm()` : `startForegroundService` avec type `microphone`.
   Android 12+ refuse ce démarrage depuis l'arrière-plan : c'est le seul moment autorisé.
2. **Écran verrouillé** → l'action de la notification ou la tuile parle à un service **déjà
   vivant**. Plus aucun démarrage, donc plus aucun refus.
3. **Capture** → `DictationRecorder(maxDurationMs = 20 s)` : personne ne relâchera le doigt,
   donc le plafond tient lieu de relâchement ; l'envoi automatique part à 20 s.
4. **Réponse** → prononcée, et affichée dans la notification (elle survit à l'écran éteint).

Ce que le service ne fait pas : la caméra. Le direct et la sonde s'arrêtent à `onPause`
(`IseeSession.onBackground`), et une photo prise en arrière-plan n'a pas de sens ici. La
question orale est donc **sans image**, et le modèle le dit s'il n'a pas de quoi répondre.

## Recette sur le téléphone

```bash
~/bin/phone run --task "install" -- adb -s 2c6852ac install -r app/build/outputs/apk/debug/app-debug.apk
~/bin/phone run --task "armer" -- adb -s 2c6852ac shell am start -n dev.sam3vision.app/.MainActivity
adb shell pm grant dev.sam3vision.app android.permission.POST_NOTIFICATIONS   # 1re fois
# verrouiller (bouton power), puis l'action « Écouter » de la notification iSee,
# ou la tuile « iSee micro » ; parler, puis « Envoyer ».
```

Attendu : notification « iSee — j'écoute » → « transcription » → « réponse » (et la voix).

## Limites connues, assumées

- **Pas de photo** : la réponse est texte seul, d'où la consigne explicite du modèle.
- **Pas d'insertion dans le chat iSee** : l'échange vit dans la notification. La prochaine étape
  serait de pousser `Done(transcript, answer)` dans `IseeViewModel` quand l'app revient.
- **20 s maximum** par question (mémoire : 32 ko/s de PCM en RAM).
- **`POST_NOTIFICATIONS`** est indispensable : sans elle la notification (donc le bouton) reste
  invisible. Elle rejoint la demande groupée du premier lancement (`FirstRunPermissions.kt`).
- Le service ne survit pas à un `force-stop` : iSee doit avoir été **ouverte** une fois depuis le
  démarrage (la tuile le dit : « Ouvre d'abord iSee »).

### Rejouer le geste sans doigt (debug)

Sur l'écran de verrouillage, `uiautomator` ne voit pas la notification (fenêtre sécurisée) et le
service n'est **pas** exporté — c'est voulu. Le manifeste de `debug` porte donc une receiver qui
appelle exactement le même geste :

```bash
adb shell am broadcast -n dev.sam3vision.app/.locked.LockedMicDebugReceiver \
    -a dev.sam3vision.app.locked.DEBUG_TOGGLE
```

Elle est absente du build `release` : aucun chemin de production ne passe par là.

## Preuves

| Quoi | Commande | Résultat |
|---|---|---|
| Règles pures | `./gradlew :core:testDebugUnitTest --offline` | `LockedMicTest` 11/11 |
| Trajet | `./gradlew :app:testDebugUnitTest --offline` | `LockedMicAnswerTest` 5/5 |
| Contrat sans image | idem | `LlmContractTest` 15/15 (`askText`, `NotSupported`) |
| Tailles | `python3 tools/check-kotlin-limits.py` | OK, aucun dépassement |
| Garde unique | `python3 tools/guard.py --quick` | OK |

Mesuré **sur le téléphone** (`2c6852ac`, build `1.0.54-0bb9794`), écran en `mWakefulness=Dozing` :

| Contrôle | Résultat |
|---|---|
| Service armé par l'activité visible | `isForeground=true foregroundId=4201 types=0x00000080` (`microphone`) |
| Geste rejoué écran verrouillé | notification « iSee — j'écoute » / « Parle, puis appuie sur « Envoyer » » |
| Micro réellement ouvert écran éteint | flux `AUDIO_FORMAT_PCM_16_BIT / 16000 Hz / scenario=record` pour `dev.sam3vision.app` dans `MQSserver` |
| Envoi automatique à 20 s | notification « iSee — échec » → « réponse inattendue : aucune parole détectée » (l'appel de transcription a bien atteint le réseau ; personne ne parlait dans le micro) |
| Amorçage des clés | **bug trouvé et corrigé sur l'appareil** : sans `seededFromBuild`, la réponse était « aucun moteur de dictée » alors que l'APK porte les clés |

**Non mesuré sur l'appareil** : la réponse du LLM et la voix ElevenLabs dans ce trajet (personne
n'a parlé dans le micro, et rien ne permet de faire entendre une phrase au téléphone depuis le
poste). Ces deux étapes sont couvertes en JVM (`LockedMicAnswerTest`) et partagent les clients déjà
utilisés par le chat ; la lecture `MediaPlayer` écran éteint reste donc à confirmer à la voix, par
toi, en une phrase.