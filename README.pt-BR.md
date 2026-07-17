# LetterForge

<div align="center">

[![English](https://img.shields.io/badge/lang-English-6e7681?style=for-the-badge)](README.md)
[![Português](https://img.shields.io/badge/lang-Portugu%C3%AAs-2ea44f?style=for-the-badge)](README.pt-BR.md)

Sistema de cartas imersivo para roleplay em Minecraft Java 1.21+

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Minecraft](https://img.shields.io/badge/Paper-1.21+-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)

</div>

---

## 📬 Descrição

O sistema de comunicação que faltava em Minecraft. Cartas escritas em livros, entregues
por um correio dentro do jogo — com caixa de correio, jornal do servidor, imagens em mapas
e ferramentas completas de moderação.

---

## ✨ Features

- 📬 **Escrever & Receber:** cartas escritas no livro e pena, entregues na caixa de correio (GUI)
- 📰 **Jornal & Broadcast:** edições do jornal e cartas para todos os jogadores (com permissão)
- 🏷️ **Prioridades:** cartas normais, IMPORTANTES e OFICIAIS, com visual distinto
- 🖼️ **Imagens em mapas:** anexe imagens via URL HTTPS ou upload pelo navegador, renderizadas em mapas
- 🛡️ **Anti-spam:** cooldowns e limites diários configuráveis, com bypass por permissão
- 🔞 **Filtro NSFW (opcional):** addon com modelo ONNX local — nenhuma imagem sai do servidor
- 🕵️ **Moderação:** auditoria de cartas anônimas, revogação e inspeção de imagens (`/carta admin`)
- 📊 **PlaceholderAPI:** `%letterforge_unread%` e `%letterforge_unread_color%`
- 💾 **Persistência:** SQLite embutido, dados salvos entre reinicializações
- 🎨 **Customizável:** todas as mensagens e a interface via YAML

---

## 🕹️ Comandos

Comando principal: `/carta` (aliases: `/mail`, `/correio`)

| Subcomando | Descrição |
|---|---|
| `/carta escrever <jogador>` | Cria o rascunho de uma carta |
| `/carta caixa` | Abre sua caixa de correio |
| `/carta todos` | Envia carta para todos os jogadores online |
| `/carta jornal` | Publica uma edição do jornal |
| `/carta imagem <url>` | Anexa uma imagem à carta |
| `/carta admin` | Ferramentas de moderação (auditoria, revogação, imagens) |
| `/carta moderar` | Fila de moderação |
| `/carta reload` | Recarrega config e mensagens |
| `/carta ajuda` | Mostra a ajuda |

## 🔐 Permissões

| Permissão | Padrão | Descrição |
|---|---|---|
| `letterforge.use` | ✅ todos | Escrever e receber cartas |
| `letterforge.broadcast` | ❌ | Enviar carta para todos |
| `letterforge.jornal` | ❌ | Publicar edições do jornal |
| `letterforge.priority.high` | ❌ | Cartas IMPORTANTES / OFICIAIS |
| `letterforge.bypass.cooldown` | ❌ | Ignorar cooldown de envio |
| `letterforge.image.url` | ❌ | Anexar imagem via URL |
| `letterforge.image.upload` | ❌ | Upload de imagem pelo navegador |
| `letterforge.image.unlimited` | ❌ | Sem limite diário de imagens |
| `letterforge.admin` | OP | Acesso total (inclui todas acima) |
| `letterforge.reload` | OP | Recarregar configurações |

---

## 🛠️ Instalação

1. Baixe o `.jar` em [Releases](../../releases)
2. Coloque em `plugins/`
3. Reinicie o servidor
4. Edite `plugins/LetterForge/config.yml` (limites, cooldowns, entrega, imagens, filtros)

**Requisitos:** Paper 1.21+, Java 21. Integrações opcionais: LuckPerms, PlaceholderAPI.

### Addon NSFW (opcional)

O core não inclui o modelo de detecção NSFW (mantém o plugin leve). Para ativar a análise
de imagens, instale o addon `LetterForge-NSFW-Model` junto ao core — ele se registra
automaticamente e roda 100% local.

---

## 🔧 Compilando

```bash
mvn -DskipTests clean package
```

O jar sai em `target/LetterForge-<versão>.jar` (Java 21, Paper API).

---

## 📄 Licença

[MIT](LICENSE) — livre para usar, modificar e redistribuir, inclusive em servidores comerciais.
Crédito é apreciado, mas não obrigatório.
