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
- **coupe tout** (chat compris, `urgent = true`), comme le stop interne ;
- **dédupliqué** : un « Stop. » déjà dit ne se répète pas — le stop interne
  dit « obstacle, arrêtez-vous. » (voix TwinLite, § 12 de
  `docs/VOIX-DANGER.md`), pas de conflit ;
- rien sans clé ElevenLabs (`ttsConfigured`), comme le reste de la voix.

Le modèle externe est câblé au niveau du ViewModel (c'est lui qui possède
`proximity`) ou d'un guetteur façon `ProbeWiring.observeCurb` — jamais dans
`LiveProbe` (499/500 lignes, tout câblage vit à côté).

## 2. En attendant : le stop interne reste

`TwinLiteWord.Stop` (« obstacle, arrêtez-vous. », contact mesuré ≤ 0,6 m,
`Guidance.STOP_METERS`) est le seul urgent de la sonde
(`LiveProbeState.spokenUrgent`, via `isStopPhrase`). Quand le modèle
externe est branché et validé, le stop interne est **supprimé** (mot, seuil,
clip `tl_stop`, tests) — la suppression est un commit à part, pas un
mélange avec le branchement.

## 3. Ce que l'urgence ne fait jamais

- Pas de texte autre que « Stop. » (un mot, < 100 ms, comme le § 10 de
  `docs/VOIX-DANGER.md`) ;
- pas de lecture d'état : l'injection est un appel direct, pas une frame ;
- pas de mode Traversée : sa voix rouge/vert reste propriétaire de son son.

Verrouillé par `ProximityVoiceWordTest` (urgence externe coupe le chat en
clip, sans synthèse) ; recette téléphone : modèle externe simulé (appel
direct) devant le chat en lecture → « Stop. » coupe et parle.
