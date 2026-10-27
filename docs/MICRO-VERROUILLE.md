# Micro de l'écran verrouillé

**Ce qui marchait mal** : appui long → le micro s'ouvre ; l'écran se verrouille → plus rien.
`MainActivity.onPause()` annule la tenue (`root.cancelGesture()` → `ihear.onHoldCancel()` →
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
(`IhearSession.onBackground`), et une photo prise en arrière-plan n'a pas de sens ici. La
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
  serait de pousser `Done(transcript, answer)` dans `IhearViewModel` quand l'app revient.
- **20 s maximum** par question (mémoire : 32 ko/s de PCM en RAM).
- **`POST_NOTIFICATIONS`** est indispensable : sans elle la notification (donc le bouton) reste
  invisible. Elle rejoint la demande groupée du premier lancement (`FirstRunPermissions.kt`).
- Le service ne survit pas à un `force-stop` : iSee doit avoir été **ouverte** une fois depuis le
  démarrage (la tuile le dit : « Ouvre d'abord iSee »).

## Preuves

| Quoi | Commande | Résultat |
|---|---|---|
| Règles pures | `./gradlew :core:testDebugUnitTest --offline` | `LockedMicTest` 11/11 |
| Trajet | `./gradlew :app:testDebugUnitTest --offline` | `LockedMicAnswerTest` 5/5 |
| Contrat sans image | idem | `LlmContractTest` 13/13 (`askText`, `NotSupported`) |
| Tailles | `python3 tools/check-kotlin-limits.py` | OK, aucun dépassement |