# Your AI Brain, Visible Inside IntelliJ

> **Cachly Brain — IntelliJ Plugin** — See your AI assistant's memory at a glance: lessons learned, tokens saved, session history, and brain health — right in your IDE's status bar.

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains-Marketplace-orange?logo=jetbrains)](https://plugins.jetbrains.com/plugin/cachly-brain)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](../../LICENSE)

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

### From JetBrains Marketplace (coming soon)
Search for **"Cachly Brain"** in **Settings → Plugins → Marketplace**.

### Manual Install
1. Download the `.zip` from [GitHub Releases](https://github.com/cachly-dev/intellij-plugin/releases)
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
- [VS Code Extension](https://github.com/cachly-dev/vscode-extension)

## License

MIT — see [LICENSE](../../LICENSE)
