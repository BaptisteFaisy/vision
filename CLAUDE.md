# CLAUDE.md — iSee (dépôt vision)

Règles complètes et opposables : [AGENTS.md](AGENTS.md) — ce fichier ne duplique
rien, il rappelle ce qui concerne les sessions Claude hors serveur.

## Machine de build : le r630, jamais ce poste

Sauf session ouverte **sur** le r630 (utilisateur `baptiste`), tout Gradle
passe par le build serveur — `./gradlew` local interdit, même pour « juste un
test rapide » :

```bash
timeout 280 tools/r630-build.sh <worktree> ":core:testDebugUnitTest :app:testDebugUnitTest assembleDebug"
python3 tools/check-kotlin-limits.py   # local, sans Gradle
```

- Prérequis : `ssh -o BatchMode=yes "${BUILD_SERVER:-baptiste@100.124.18.48}" true`.
  Si rouge : demander à l'utilisateur (clé à autoriser), ne pas replier en local.
- Le script envoie le worktree (sans `.git`) + `local.properties`, lance Gradle
  sur le r630 (cache chaud, mesuré 1 min 8 s pour `:app:assembleDebug`) et liste
  les APK produits sur le serveur.
- Détail et variantes : `AGENTS.md` §2, `tools/r630-build.sh`.
