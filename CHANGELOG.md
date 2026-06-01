# Changelog – Cachly Brain IntelliJ Plugin

---

## [0.3.0] – 2026-06-01

### Added

- **Set Up AI Files** action (Tools → Cachly Brain) — writes `.mcp.json`, `CLAUDE.md`, `AGENTS.md` and `.github/copilot-instructions.md` with the full Brain lifecycle protocol (session_start / smart_recall / remember_context / learn_from_attempts / causal_trace / session_end), plus the CLS git `post-commit` hook. Idempotent and non-destructive (marker-based), mirroring the VS Code extension and MCP autopilot.
- **Run Brain Doctor** action — ✓/✗ diagnostics for API URL, key presence + format, instance ID presence + format, live instance reachability and memory readability, with a one-click jump to Settings.
- JSONC-tolerant `.mcp.json` parsing so existing configs with comments are preserved on merge.

---

## [0.2.2] – 2026-04-21

### Added

- **Team Brain awareness** — Brain Health dialog now shows team lesson count with author attribution (`👥 3 team lessons · Elena, Tom`)
- **brain_doctor integration** — IQ Boost % and Crystal freshness shown in Brain Health panel
- **Memory Crystal indicator** — status bar shows 💎 when a Crystal is injected at session start

### Fixed

- Status bar widget not refreshing after `session_start` completes
- Balloon notification appearing multiple times on project re-open

---

## [0.2.0] – 2026-04-19

### Added

- **Ambient Learning** — `AmbientLearningService` detects repeated typing patterns across all open editors (Dice bigram similarity ≥ 0.75, 3+ times) and offers to save them as Brain lessons via balloon notification
- **Framework Detection** — `FrameworkDetectionStartup` scans `package.json`, `go.mod`, `requirements.txt`, `build.gradle` on project open and notifies which stack is detected
- **Cost savings tracker** — status bar shows `🧠 Brain: 23 · ~$0.84 saved` when `showCostSaved` is enabled
- **First-hit notification** — on first successful Brain load, shows a balloon confirming the Brain is active with lesson count
- **Save Lesson action** — `Tools → Save Lesson to Brain` (shortcut: `Ctrl+Shift+B`) opens a dialog to store any solution; also triggered from ambient learning suggestions
- **New settings**: `showCostSaved`, `ambientLearning` (in Settings → Tools → Cachly Brain)
- `notificationGroup` registered for all balloon notifications

---

## [0.1.0] – 2026-04-07

### Added

- Initial release: status bar widget with lesson count, brain health dialog, lessons dialog, settings panel
