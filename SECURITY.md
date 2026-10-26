# Sécurité

**Ne jamais ouvrir d'issue publique pour une faille.** Écris à l'adresse de contact du profil
GitHub `@BaptisteFaisy` (ou un message privé) avec : la version/le commit, ce qui est touché,
et la reproduction minimale. Réponse sous 7 jours.

Le dépôt ne contient **aucune clé** : les clés (LLM, TTS, ASR) vivent dans `local.properties`
(gitignoré), dans le Keystore Android de l'appareil, ou dans les secrets du dépôt pour les
builds que tu publies toi-même. La CI n'en utilise aucune.

Le *secret scanning* et la *push protection* GitHub sont activés : un commit qui contient une
clé est bloqué avant d'entrer.