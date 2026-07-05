# MiniBridge 🌉

[![GitHub Release](https://img.shields.io/github/v/release/herocraftlol/Discord-Link)](https://github.com/herocraftlol/Discord-Link/releases)
[![Java Version](https://img.shields.io/badge/Java-21-blue)](https://www.java.com/)
[![Paper Version](https://img.shields.io/badge/Paper-1.21-green)](https://papermc.io/)

Plugin léger **Paper 1.21** pour relier votre serveur Minecraft à Discord.  
**Aucune dépendance externe** — pas de JDA, pas de fuite mémoire Netty.

---

## Fonctionnalités

| Minecraft → Discord | Discord → Minecraft |
|---|---|
| ✅ Messages du chat | ✅ Messages relayés en jeu |
| ✅ Connexions / déconnexions | ✅ Commande `!list` |
| ✅ Morts de joueurs | ✅ Commande `!tps` |
| ✅ Advancements | ✅ Commande `!time` |
| ✅ Démarrage / arrêt serveur | |

---

## Installation

### 1. Créer un Bot Discord

1. Allez sur https://discord.com/developers/applications
2. **New Application** → donnez un nom
3. Onglet **Bot** → **Add Bot**
4. Copiez le **Token** (gardez-le secret !)
5. Activez **Message Content Intent** (dans Bot > Privileged Gateway Intents)
6. Onglet **OAuth2 > URL Generator** :
   - Scopes : `bot`
   - Permissions : `Send Messages`, `Read Message History`, `View Channels`
7. Copiez l'URL générée et invitez le bot sur votre serveur

### 2. Récupérer l'ID du salon

1. Dans Discord : **Paramètres > Avancés > Mode développeur** → activer
2. Clic droit sur le salon cible → **Copier l'identifiant**

### 3. Configurer le plugin

Placez `MiniBridge.jar` dans `/plugins/` et démarrez le serveur une fois pour générer `config.yml`.

Éditez `plugins/MiniBridge/config.yml` :

```yaml
bot-token: "VOTRE_TOKEN_ICI"
channel-id: "VOTRE_CHANNEL_ID_ICI"
```

### 4. (Optionnel) Mode Webhook

Si vous ne voulez pas de bot complet (pas de Discord → Minecraft) :

1. Dans Discord : Paramètres du salon → Intégrations → Webhooks → Créer
2. Copiez l'URL et dans config.yml :

```yaml
use-webhook: true
webhook-url: "VOTRE_URL_WEBHOOK"
```

---

## Commandes Minecraft

| Commande | Description |
|---|---|
| `/minibridge reload` | Recharge la configuration |

## Commandes Discord

| Commande | Description | Permission |
|---|---|---|
| `!list` | Liste les joueurs en ligne | Rôle admin |
| `!tps` | Affiche le TPS du serveur | Rôle admin |
| `!time` | Affiche l'heure en jeu | Rôle admin |

---

## Compilation

```bash
mvn clean package
```

Le jar final se trouve dans `target/MiniBridge-1.0.0.jar`.

---

## Pourquoi pas JDA ?

JDA utilise Netty pour les WebSockets, ce qui peut causer des fuites de direct memory (exactement le problème que vous rencontrez avec DiscordSRV). MiniBridge utilise le **client HTTP natif de Java 21** (`java.net.http.WebSocket`) — zéro dépendance réseau externe.

---

## 📥 Download

Téléchargez la dernière version : [MiniBridge v1.0.3](https://github.com/herocraftlol/Discord-Link/releases/latest)

---

## Configuration du rôle admin

Pour utiliser les commandes Discord (`!list`, `!tps`, `!time`), vous devez spécifier l'ID du rôle admin dans `config.yml` :

1. **Activer le Mode Développeur** : Paramètres Discord → Avancés → Mode Développeur
2. **Clic droit sur le rôle** → Copier l'identifiant
3. Dans `config.yml` :
```yaml
admin-role-id: "1234567890123456789"
```

Si vous laissez `admin-role-id` vide, les commandes seront désactivées.
