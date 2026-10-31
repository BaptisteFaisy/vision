# Modèle d'urgence externe — contrat de branchement

Ordre user du 29/10 : iSee/SAM sert aux situations **non urgentes** (guidage
doux, « Attention à … ») ; un **autre modèle** (en cours) gère l'urgence.
Ce contrat dit comment il parle, en attendant son branchement.

## 1. Parler : `ProximityVoice.reportEmergency`

```kotlin
proximity.reportEmergency(settings.speechLanguage, playerIdle = canAnnounce())
```

- Dit « Stop. » (FR/EN, langue de la voix) **aussitôt**, en clip local
  (< 100 ms, zéro réseau) quand le pack voix l'a, sinon synthèse + cache ;
- **coupe tout** (chat compris, `urgent = true`), comme le stop Traversée ;
- **dédupliqué** : un « Stop. » déjà dit ne se répète pas (le stop
  interne du guidage est supprimé depuis l'ordre user du 31/10 —
  jamais de stop pour un obstacle) ;
- rien sans clé ElevenLabs (`ttsConfigured`), comme le reste de la voix.

Le modèle externe est câblé au niveau du ViewModel (c'est lui qui possède
`proximity`) ou d'un guetteur façon `ProbeWiring.observeCurb` — jamais dans
`LiveProbe` (499/500 lignes, tout câblage vit à côté).

## 2. Le stop interne est supprimé (ordre user du 31/10)

`TwinLiteWord.Stop` (« obstacle, arrêtez-vous. », ex-contact mesuré
≤ 0,6 m, `Guidance.STOP_METERS` supprimé) ne se dit plus : un obstacle
dit son côté de sortie, jamais stop. Le mot et le clip `tl_stop` restent
au catalogue (miroir `tools/voicepack-catalog.json`, inutilisés). Seul
urgent restant de la sonde : « Stop, car moving. » en Traversée et
« Stop. » du modèle externe, via `spokenUrgentOf`.

## 3. Ce que l'urgence ne fait jamais

- Pas de texte autre que « Stop. » (un mot, < 100 ms, comme le § 10 de
  `docs/VOIX-DANGER.md`) ;
- pas de lecture d'état : l'injection est un appel direct, pas une frame ;
- pas de mode Traversée : sa voix rouge/vert reste propriétaire de son son.

Verrouillé par `ProximityVoiceWordTest` (urgence externe coupe le chat en
clip, sans synthèse) ; recette téléphone : modèle externe simulé (appel
direct) devant le chat en lecture → « Stop. » coupe et parle.
