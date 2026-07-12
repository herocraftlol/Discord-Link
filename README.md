# MiniBridge 🌉

**🎮 Bridge Minecraft ⇄ Discord pour serveur Paper 1.21**

[![Version](https://img.shields.io/github/v/release/herocraftlol/Discord-Link?include_prereleases&label=version)](https://github.com/herocraftlol/Discord-Link/releases)
[![Java](https://img.shields.io/badge/java-21-blue.svg)](https://www.java.com/)
[![Paper](https://img.shields.io/badge/paper-1.21-cyan.svg)](https://papermc.io/)
[![License](https://img.shields.io/github/license/herocraftlol/Discord-Link.svg)]()

> **MiniBridge** est le plugin ultime pour connecter votre serveur Minecraft à Discord sans friction. Léger, rapide, et **sans aucune dépendance externe** — exit JDA et ses fuites mémoire Netty !

---

## 🚀 Pourquoi MiniBridge ?

| | MiniBridge | DiscordSRV |
|---|---|---|
| **Dépendances** | Aucune (HTTP natif Java 21) | JDA + Netty (lourd) |
| **Mémoire** | Optimisée | Fuites de direct memory |
| **Performance** | WebSocket natif | WebSocket externe |
| **Configuration** | Simple et rapide | Complexe |

---

## 📥 Téléchargement

Téléchargez la dernière version sur la [page des releases](https://github.com/herocraftlol/Discord-Link/releases).

---

## ✨ Fonctionnalités complètes

### 🔄 Communication bidirectionnelle en temps réel

| Minecraft → Discord | Discord → Minecraft |
|---|---|
| ✅ **Messages du chat** — Tous les messages en jeu sont transmis instantanément | ✅ **Messages relayés** — Les messages Discord apparaissent en jeu en temps réel |
| ✅ **Connexions** — Notification de join avec style | ✅ **Commande `!list`** — Liste tous les joueurs en ligne |
| ✅ **Déconnexions** — Notification de leave détaillée | ✅ **Commande `!tps`** — Affiche le TPS et状态 du serveur |
| ✅ **Morts de joueurs** — Message de mort avec cause | ✅ **Commande `!time`** — Affiche l'heure Minecraft |
| ✅ **Advancements** — Célébre les achievements majeurs | ✅ **Commande `!info`** — Informations complètes du serveur |
| ✅ **Démarrage** — "Serveur démarré !" à chaque boot | ✅ **Commande `!help`** — Liste toutes les commandes |
| ✅ **Arrêt** — Notification propre avant extinction | |
| ✅ **Messages système** — Broadcasts importants | |

### 🔐 Sécurité et permissions

- **Rôle Admin configurable** — Seuls les membres Discord avec le rôle défini peuvent exécuter les commandes
- **Contrôle d'accès granulaire** — Vous décidez qui peut interagir avec le serveur

### 🎨 Personnalisation

- **Mode Webhook optionnel** — Pour une intégration simple sans bot complet
- **Configuration YAML** — Facile à modifier et à étendre
- **Reload à chaud** — `/minibridge reload` sans redémarrer le serveur

### 🖼️ Affichage des skins des joueurs

> **NOUVEAU !** MiniBridge peut désormais afficher les **skins Minecraft** des joueurs comme avatars Discord !

Tous les messages (chat, join, quit, death, advancement) affichent automatiquement le skin du joueur.

#### Services disponibles

| Service | Style | URL |
|---|---|---|
| **mc-heads.net** (défaut) | Avatar pixelisé Minecraft | https://mc-heads.net |
| **mineskin.eu** | Render 3D du skin | https://mineskin.eu |
| **crafatar.com** | Avatars alternatifs | https://crafatar.com |

#### Configuration

Dans `config.yml`, ajoutez :

```yaml
# Mode Webhook requis pour les skins
use-webhook: true
webhook-url: "VOTRE_WEBHOOK_URL"

# Service d'avatar (optionnel, défaut: mc-heads)
avatar-service: "mc-heads"
```

> ⚠️ **Note** : Les avatars ne fonctionnent qu'en mode Webhook. Discord ne permet pas les avatars personnalisés pour les messages de bots.

---

## 🚀 Installation pas à pas

### 1. 🎯 Créer un Bot Discord

1. Allez sur [Discord Developer Portal](https://discord.com/developers/applications)
2. Cliquez **New Application** → donnez un nom (ex: "Mon Serveur Bridge")
3. Allez dans l'onglet **Bot** → cliquez **Add Bot**
4. Dans **Token**, cliquez **Reset Token** et **copiez-le** (gardez-le secret !)
5. Dans **Privileged Gateway Intents**, activez :
   - ✅ **Message Content Intent** (requis pour lire les messages)
6. Allez dans **OAuth2 > URL Generator** :
   - Cochez Scopes : `bot`
   - Cochez Permissions : `Send Messages`, `Read Message History`, `View Channels`
7. **Copiez l'URL** et collez-la dans votre navigateur pour inviter le bot

### 2. 🆔 Récupérer les IDs nécessaires

1. Dans Discord : **Paramètres utilisateur > Avancés** → activer le **Mode développeur**
2. **Récupérer l'ID du salon** : Clic droit sur le salon Discord → **Copier l'identifiant**
3. **Récupérer l'ID du rôle admin** (optionnel) : Clic droit sur le rôle → **Copier l'identifiant**

### 3. ⚙️ Configurer le plugin

1. Téléchargez `MiniBridge-1.0.0.jar` depuis la [page des releases](https://github.com/herocraftlol/Discord-Link/releases)
2. Placez-le dans `/plugins/` de votre serveur Paper
3. **Démarrez le serveur** une fois pour générer `config.yml`
4. Éditez `plugins/MiniBridge/config.yml` :

```yaml
# === CONFIGURATION MINIBRIDGE ===

# Token du bot Discord (obtenu depuis le Developer Portal)
bot-token: "VOTRE_BOT_TOKEN_ICI"

# ID du salon Discord où le bot enverra/recevra les messages
channel-id: "VOTRE_CHANNEL_ID_ICI"

# ID du rôle Discord autorisé à utiliser les commandes (optionnel)
admin-role-id: "VOTRE_ROLE_ID_ICI"

# Mode webhook (true = lecture seule, pas de bot complet)
use-webhook: false
webhook-url: ""

# Préfixe des commandes Discord (!list, !tps, etc.)
command-prefix: "!"
```

5. **Redémarrez le serveur** ou utilisez `/minibridge reload`

### 4. 🔗 Mode Webhook (alternative légère)

Si vous voulez seulement recevoir les messages Minecraft dans Discord (sans bot) :

1. Dans Discord : Paramètres du salon → **Intégrations** → **Webhooks** → **Nouveau webhook**
2. Nommez-le (ex: "Minecraft Bridge") et **copiez l'URL**
3. Dans `config.yml` :

```yaml
use-webhook: true
webhook-url: "https://discord.com/api/webhooks/XXXXX/YYYYY"
```

> ⚠️ Le mode webhook est **lecture seule** — les messages Discord ne seront pas relayés vers Minecraft.

---

## 🎮 Commandes disponibles

### 📋 Commandes Minecraft (In-Game)

| Commande | Description | Permission |
|---|---|---|
| `/minibridge reload` | 🔄 Recharge la configuration sans redémarrer | `minibridge.reload` |
| `/minibridge status` | 📊 Affiche le statut de la connexion Discord | `minibridge.status` |

### 💬 Commandes Discord (dans le chat)

| Commande | Description | Rôle requis |
|---|---|---|
| `!list` | 📋 Affiche la liste complète des joueurs en ligne | Admin |
| `!tps` | 📈 Affiche le TPS du serveur (Tick Per Second) | Admin |
| `!time` | 🕐 Affiche l'heure actuelle dans Minecraft | Admin |
| `!info` | ℹ️ Affiche les informations du serveur (version, joueurs, uptime) | Admin |
| `!help` | ❓ Affiche la liste de toutes les commandes disponibles | Admin |

> 💡 **Note** : Seuls les membres ayant le rôle admin configuré peuvent utiliser les commandes Discord.

---

## 🔧 Compilation depuis les sources

```bash
# Cloner le dépôt
git clone https://github.com/herocraftlol/Discord-Link.git
cd Discord-Link

# Compiler avec Maven
mvn clean package

# Le JAR compilé se trouve dans :
# target/MiniBridge-1.0.0.jar
```

### Prérequis pour la compilation

- **Java 21** (ou supérieur)
- **Maven 3.6+**

---

## 🧪 Technologies utilisées

| Technologie | Utilisation |
|---|---|
| **Java 21** | Langage de programmation principal |
| **Paper API 1.21** | API Minecraft pour插件 développement |
| **java.net.http.WebSocket** | Communication WebSocket native (pas de Netty !) |
| **json-simple** | Parsing JSON léger pour les payloads Discord |

---

## 🤔 Pourquoi MiniBridge et pas DiscordSRV ?

### Le problème avec DiscordSRV

DiscordSRV utilise **JDA (Java Discord API)** qui elle-même repose sur **Netty** pour les WebSockets. Cela cause :

- ❌ **Fuites de direct memory** — Netty alloue de la mémoire native qui n'est pas toujours libérée
- ❌ **Consommation RAM élevée** — JDA + Netty = plusieurs centaines de MB
- ❌ **Configuration complexe** — Des dizaines de fichiers YAML à configurer
- ❌ **Dependances lourdes** — Des dizaines de JARs à charger

### La solution MiniBridge

MiniBridge utilise le **client HTTP natif de Java 21** (`java.net.http.HttpClient` et `WebSocket`) :

- ✅ **Zéro dépendance réseau externe** — Pas de JDA, pas de Netty
- ✅ **Mémoire optimisée** — Le client HTTP de Java est natif et optimisé
- ✅ **Configuration simple** — Un seul fichier `config.yml`
- ✅ **Léger** — Seulement ~64KB pour le plugin complet

---

## 📄 Licence

Ce projet est sous licence **MIT**. Vous êtes libre de l'utiliser, le modifier et le distribuer.

---

## 🤝 Contribuer

Les contributions sont les bienvenues ! N'hésitez pas à :

1. Forker le dépôt
2. Créer une branche (`git checkout -b feature/ma-fonctionnalite`)
3. Commiter vos changements (`git commit -m 'Ajout d'une nouvelle fonctionnalité'`)
4. Push sur la branche (`git push origin feature/ma-fonctionnalite`)
5. Ouvrir une Pull Request

---

<div align="center">

**⭐ N'hésitez pas à star ce projet si vous l'appréciez ! ⭐**

Fait avec ❤️ pour la communauté Minecraft francophone

</div>
