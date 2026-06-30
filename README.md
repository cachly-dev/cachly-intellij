# 🧠 Your AI Brain, Visible Inside IntelliJ

> **Your AI forgets everything when the IDE closes. This plugin makes it remember — and shows you proof.**  
> See your AI assistant's memory at a glance: lessons learned, tokens saved, session history, and brain health — right in your IDE's status bar. Works in every JetBrains IDE.

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
  <img src="https://img.shields.io/badge/License-Apache--2.0-yellow" alt="License" />
</p>

<p align="center">
  <a href="https://cachly.dev?utm_source=jetbrains-marketplace&utm_medium=readme&utm_campaign=plugin-cta">
    <img src="https://img.shields.io/badge/%E2%96%B6_Get_Your_Free_Brain-cachly.dev-7c3aed?style=for-the-badge" alt="Get Free Brain" />
  </a>
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
# Output: build/distributions/cachly-brain-0.2.0.zip
```

---

## Links

- [cachly.dev](https://cachly.dev) — Dashboard & free signup
- [AI Brain docs](https://cachly.dev/docs/ai-memory) — MCP server setup
- [MCP Server npm](https://www.npmjs.com/package/@cachly-dev/mcp-server) — The brain backend
- [VS Code Extension](https://marketplace.visualstudio.com/items?itemName=cachly-dev.cachly-brain)

## License

MIT — see [LICENSE](../../LICENSE)
