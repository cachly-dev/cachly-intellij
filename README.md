# 🧠 Your AI Brain, Visible Inside IntelliJ

> **ChatGPT and Claude remember your conversations. cachly remembers your codebase.**  
> The bug you fixed. Why you chose Postgres. The deploy step that always breaks — and everything your teammates learned. It stays when someone leaves the team, and it comes along when you switch assistants.
>
> **This plugin puts that memory in front of you** — lessons learned, tokens saved, session history and brain health, right in your IDE's status bar. Works in every JetBrains IDE.
>
> **Nothing to sign up for.** On first start the plugin fetches a free trial brain by itself — no account, no credit card, no key to paste. Link an account later to keep the data. EU servers, GDPR.

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/32059-cachly-brain">
    <img src="https://img.shields.io/jetbrains/plugin/v/32059-cachly-brain?logo=jetbrains&label=JetBrains%20Marketplace" alt="JetBrains Marketplace" />
  </a>
  &nbsp;
  <a href="https://plugins.jetbrains.com/plugin/32059-cachly-brain">
    <img src="https://img.shields.io/jetbrains/plugin/d/32059-cachly-brain?label=installs" alt="Installs" />
  </a>
  &nbsp;
  <a href="https://plugins.jetbrains.com/plugin/32059-cachly-brain/reviews">
    <img src="https://img.shields.io/jetbrains/plugin/r/rating/32059-cachly-brain?label=rating" alt="Rating" />
  </a>
  &nbsp;
  <a href="https://cachly.dev?utm_source=jetbrains-marketplace&utm_medium=readme&utm_campaign=plugin">
    <img src="https://img.shields.io/badge/Free%20Brain-%E2%82%AC0%2Fmo%20forever-brightgreen" alt="Free Brain" />
  </a>
  &nbsp;
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-Elastic--2.0-blue" alt="License" />
  </a>
</p>

<p align="center">
  <a href="https://cachly.dev?utm_source=jetbrains-marketplace&utm_medium=readme&utm_campaign=plugin-cta">
    <img src="https://img.shields.io/badge/%E2%96%B6_Get_Your_Free_Brain-cachly.dev-7c3aed?style=for-the-badge" alt="Get Free Brain" />
  </a>
</p>

<p align="center">
  <img src="https://cachly.dev/screenshots/intellij/shot1-hero.png" alt="Cachly Brain in a JetBrains IDE: status bar with lesson count, briefing on project open" width="720" />
</p>
<p align="center">
  <img src="https://cachly.dev/screenshots/intellij/shot3-lessons.png" alt="Lesson viewer: every learned lesson with recall count and severity" width="720" />
  <br />
  <img src="https://cachly.dev/screenshots/intellij/shot2-doctor.png" alt="Brain Doctor: checks API key, instance and network, and names the fix" width="720" />
</p>

---

## What This Plugin Does

You've set up the cachly MCP server. Your AI assistant is now learning and remembering. But how do you know it's working? How many lessons has it stored? How much money has it saved you?

**This plugin makes your AI's brain visible.** Status bar widget, health dialog, lessons table — inside any JetBrains IDE.

---

## Features

- **Status bar widget** — Live lesson count and brain health (`🧠 Brain: 42 lessons`)
- **Brain Health Dialog** — Storage usage, tier, recalls, estimated tokens & cost saved
- **Lessons View** — All lessons with topic, outcome, recall count, and what worked
- **Auto-refresh** — Configurable interval (default 5 minutes)
- **All JetBrains IDEs** — IntelliJ IDEA, WebStorm, PyCharm, GoLand, Rider, and more

---

## Setup

The IntelliJ action-to-capability map is tracked in
[`src/main/resources/cachly-capabilities.json`](src/main/resources/cachly-capabilities.json)
and rendered in [`../../docs/generated/surface-parity.md`](../../docs/generated/surface-parity.md).

### From JetBrains Marketplace (recommended)
**[Install Cachly Brain →](https://plugins.jetbrains.com/plugin/32059-cachly-brain)** or search for **"Cachly Brain"** in **Settings → Plugins → Marketplace**.

### Manual Install
1. Download the `.zip` from [GitHub Releases](https://github.com/cachly-dev/cachly-intellij/releases)
2. **Settings → Plugins → ⚙️ → Install Plugin from Disk** → select the `.zip`
3. **Settings → Tools → Cachly Brain** and set:

| Setting | Description |
|---------|-------------|
| API Key | Your Cachly API key (`cky_live_...`) from [cachly.dev](https://cachly.dev) |
| Instance ID | Your Brain instance UUID |
| Refresh Interval | Status bar refresh in seconds (default: 300) |

---

## With vs. Without the Plugin

| | Without plugin | With plugin |
|--|---------------|------------|
| Brain health | Unknown | Live status bar |
| Lesson count | Check elsewhere | Visible in IDE |
| Tokens saved | No idea | Shown in dialog |
| Session recall | Invisible | See it happen |

---

## Pricing

The plugin is free. It connects to your cachly Brain instance:

| Tier | RAM | Price |
|------|-----|-------|
| **Free** | 25 MB | €0/mo |
| **Dev** | 200 MB | €19/mo |
| **Pro** | 900 MB | €49/mo |
| **Speed** | 900 MB Dragonfly + Semantic Cache | €79/mo |
| **Business** | 7 GB | €199/mo |

---

## Build

```bash
cd sdk/intellij
./gradlew buildPlugin
# Ergebnis: build/distributions/cachly-brain-<version>.zip
# Die Version kommt aus build.gradle.kts. Hier stand bis zum 17.08.2026 eine
# feste 0.2.0, waehrend das Plugin bei 0.7.2 war — eine Zahl an zwei Orten
# gepflegt laeuft immer auseinander, und eine veraltete liest sich wie ein
# verlassenes Projekt.
```

---

## Links

- [cachly.dev](https://cachly.dev) — Dashboard & free signup
- [AI Brain docs](https://cachly.dev/docs/ai-memory) — MCP server setup
- [MCP Server npm](https://www.npmjs.com/package/@cachly-dev/mcp-server) — The brain backend
- [VS Code Extension](https://marketplace.visualstudio.com/items?itemName=cachly-dev.cachly-brain) — same brain, same trial, for VS Code
- [Blog](https://cachly.dev/blog) — how we build this, including the mistakes

## License

[Elastic License 2.0](LICENSE) — same terms as the rest of the cachly repository.
