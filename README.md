# LetterForge

<div align="center">

[![English](https://img.shields.io/badge/lang-English-2ea44f?style=for-the-badge)](README.md)
[![Português](https://img.shields.io/badge/lang-Portugu%C3%AAs-6e7681?style=for-the-badge)](README.pt-BR.md)

Immersive letter system for roleplay on Minecraft Java 1.21+

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Minecraft](https://img.shields.io/badge/Paper-1.21+-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)

</div>

---

## 📬 What is it?

The communication system Minecraft was missing. Letters written in books, delivered by an
in-game mail service — with a mailbox, server newspaper, map-rendered images and full
moderation tools.

---

## ✨ Features

- 📬 **Write & Receive:** letters written with book and quill, delivered to a mailbox (GUI)
- 📰 **Newspaper & Broadcast:** newspaper editions and letters to every player (permission-gated)
- 🏷️ **Priorities:** normal, IMPORTANT and OFFICIAL letters, each with distinct visuals
- 🖼️ **Map images:** attach images via HTTPS URL or browser upload, rendered on maps
- 🛡️ **Anti-spam:** configurable cooldowns and daily limits, with permission bypass
- 🔞 **NSFW filter (optional):** addon with a local ONNX model — no image ever leaves the server
- 🕵️ **Moderation:** anonymous letter auditing, revocation and image inspection (`/carta admin`)
- 📊 **PlaceholderAPI:** `%letterforge_unread%` and `%letterforge_unread_color%`
- 💾 **Persistence:** embedded SQLite, data saved across restarts
- 🎨 **Customizable:** every message and the whole interface via YAML

---

## 🕹️ Commands

Main command: `/carta` (aliases: `/mail`, `/correio`)

| Subcommand | Description |
|---|---|
| `/carta escrever <player>` | Create a letter draft |
| `/carta caixa` | Open your mailbox |
| `/carta todos` | Send a letter to every online player |
| `/carta jornal` | Publish a newspaper edition |
| `/carta imagem <url>` | Attach an image to the letter |
| `/carta admin` | Moderation tools (audit, revoke, images) |
| `/carta moderar` | Moderation queue |
| `/carta reload` | Reload config and messages |
| `/carta ajuda` | Show help |

English subcommand aliases also work: `write`, `inbox`, `all`/`broadcast`, `news`, `img`/`image`.

## 🔐 Permissions

| Permission | Default | Description |
|---|---|---|
| `letterforge.use` | ✅ everyone | Write and receive letters |
| `letterforge.broadcast` | ❌ | Send a letter to everyone |
| `letterforge.jornal` | ❌ | Publish newspaper editions |
| `letterforge.priority.high` | ❌ | IMPORTANT / OFFICIAL letters |
| `letterforge.bypass.cooldown` | ❌ | Ignore send cooldown |
| `letterforge.image.url` | ❌ | Attach image via URL |
| `letterforge.image.upload` | ❌ | Upload image from the browser |
| `letterforge.image.unlimited` | ❌ | No daily image limit |
| `letterforge.admin` | OP | Full access (includes all above) |
| `letterforge.reload` | OP | Reload configuration |

---

## 🛠️ Installation

1. Download the `.jar` from [Releases](../../releases)
2. Drop it into `plugins/`
3. Restart the server
4. Edit `plugins/LetterForge/config.yml` (limits, cooldowns, delivery, images, filters)

**Requirements:** Paper 1.21+, Java 21. Optional integrations: LuckPerms, PlaceholderAPI.

### NSFW addon (optional)

The core ships without the NSFW detection model (keeps the plugin lightweight). To enable
image scanning, install the `LetterForge-NSFW-Model` addon next to the core — it registers
itself automatically and runs 100% locally.

---

## 🔧 Building

```bash
mvn -DskipTests clean package
```

The jar is produced at `target/LetterForge-<version>.jar` (Java 21, Paper API).

---

## 📄 License

[MIT](LICENSE) — free to use, modify and redistribute, including on commercial servers.
Attribution is appreciated but not required.
