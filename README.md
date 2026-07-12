# MiniBridge 🌉

**Plugin Minecraft-Discord bridge pour Paper 1.21 avec affichage des skins !**

[![Version](https://img.shields.io/github/v/release/herocraftlol/Discord-Link?include_prereleases&label=version)](https://github.com/herocraftlol/Discord-Link/releases)
[![Java](https://img.shields.io/badge/java-21-blue.svg)](https://www.java.com/)
[![Paper](https://img.shields.io/badge/paper-1.21-cyan.svg)](https://papermc.io/)

> Plugin léger pour relier votre serveur Minecraft à Discord.  
> **Aucune dépendance externe** — pas de JDA, pas de fuite mémoire Netty.

---

## ✨ Fonctionnalités

### 🎮 Minecraft → Discord

| Fonctionnalité | Description |
|---|---|
| ✅ **Messages du chat** | Relayés avec le skin du joueur en avatar |
| ✅ **Connexions** | Notification avec le skin du joueur |
| ✅ **Déconnexions** | Notification avec le skin du joueur |
| ✅ **Morts** | Message de mort avec le skin du joueur |
| ✅ **Advancements** | Notification des achievements majeurs |
| ✅ **Démarrage/Arrêt** | Notification du serveur |

### 💬 Discord → Minecraft

| Fonctionnalité | Description |
|---|---|
| ✅ **Messages relayés** | Les messages Discord apparaissent en jeu |
| ✅ **Commande `!list`** | Liste les joueurs en ligne |
| ✅ **Commande `!tps`** | Affiche le TPS du serveur |
| ✅ **Commande `!time`** | Affiche l'heure en jeu |

### 📋 Console Relay (NOUVEAU !)

Reliez la console du serveur Minecraft vers un salon Discord dédié :
- Logs groupés pour éviter le spam
- Configuration du niveau de log (INFO, WARNING, SEVERE...)
- Filtrage des messages à ignorer

---

## 🖼️ Skins des joueurs

MiniBridge affiche automatiquement les **skins Minecraft** des joueurs comme avatars Discord !

Les messages de chat, connexion, déconnexion et mort utilisent le skin du joueur.

**Configuration optionnelle :**
```yaml
skins:
  avatar-url: "https://mc-heads.net/avatar/{player}/100"
  embed-color: "57F287"
```

---

## 🚀 Installation

### 1. Créer un Bot Discord

1. Allez sur https://discord.com/developers/applications
2. **New Application** → donnez un nom
3. Onglet **Bot** → **Add Bot**
4. Copiez le **Token** (gardez-le secret !)
5. Activez **Message Content Intent** (dans Bot > Privileged Gateway Intents)
6. **OAuth2 > URL Generator** : Scopes `bot`, Permissions `Send Messages`, `Read Message History`, `View Channels`
7. Invitez le bot sur votre serveur

### 2. Configurer le plugin

Placez `MiniBridge.jar` dans `/plugins/` et démarrez le serveur pour générer `config.yml`.

Éditez `plugins/MiniBridge/config.yml` :
```yaml
bot-token: "VOTRE_TOKEN_ICI"
channel-id: "VOTRE_CHANNEL_ID_ICI"
```

### 3. Mode Webhook (optionnel)

Pour les skins des joueurs sans bot complet :
```yaml
use-webhook: true
webhook-url: "VOTRE_URL_WEBHOOK"
```

### 4. Console Relay (optionnel)

Pour relier la console vers Discord :
```yaml
console:
  enabled: true
  webhook-url: "VOTRE_WEBHOOK_CONSOLE"
  level: "INFO"
  flush-interval-ms: 3000
```

---

## 🎮 Commandes

### Minecraft
| Commande | Description |
|---|---|
| `/minibridge reload` | Recharge la configuration |

### Discord
| Commande | Description |
|---|---|
| `!list` | Joueurs en ligne |
| `!tps` | TPS du serveur |
| `!time` | Heure Minecraft |

---

## 🔧 Compilation

```bash
mvn clean package
```

Le JAR final : `target/MiniBridge-1.0.0.jar`

---

## 🤔 Pourquoi MiniBridge ?

| | MiniBridge | DiscordSRV |
|---|---|---|
| **Dépendances** | Aucune (HTTP natif Java 21) | JDA + Netty |
| **Mémoire** | Optimisée | Fuites de RAM |
| **Skins** | ✅ Affichés | ❌ Non |
| **Console Relay** | ✅ Inclus | ❌ Non |

MiniBridge utilise le **client HTTP natif de Java 21** — zéro dépendance réseau externe.
