# Changelog – Cachly Brain IntelliJ Plugin

---

## [0.7.1] – 2026-08-12 — *"Compatibility restored"*

### Fixed
- **Plugin works on IDEs after 2025.2.** The version 0.7.0 had a hard compatibility
  limit (untilBuild) that silently deactivated the plugin on IntelliJ IDEA 2025.3+
  and all newer JetBrains IDEs. This was not a functional change — the code ran
  unchanged — but users saw the plugin greyed out on IDE update. Fixed by removing
  the hard limit.

---

## [0.7.0] – 2026-07-19 — *"Your counter survives a restart"*

### Fixed
- **Recall counter no longer flickers to zero after a restart.** The first
  health fetch after opening the IDE can run before the network is up (or while
  the instance is still waking) and come back zeroed. The plugin now persists
  the last snapshot that actually carried data (`PersistentStateComponent`) and
  shows it — with a `⟳` hint in the status bar — until a good fetch replaces it,
  instead of briefly repainting your lessons/recalls as 0. Parity with the VS
  Code extension 0.12.2.

### Added
- **Quiet mode** — a Settings toggle that turns off every proactive popup at
  once. The status bar and Brain panel keep updating.
- **One notification budget for the whole plugin.** Framework detection, the
  ambient "save this?" prompt, the startup briefing and per-file warnings now
  share a single budget: at most one popup every 20 minutes, three per IDE
  session. Framework detection in particular no longer fires unconditionally on
  every project open.

---

## [0.6.0] – 2026-07-15 — *"Explainable fix hints"*

### Added
- **Per-file fix hints** with provenance — opening a file the Brain knows has a
  failure pattern shows a warning with severity/confidence plus *Show fix* (full
  lesson card), *Copy fix*, and *Not helpful* (suppresses that lesson for that
  file, remembered across restarts). Hint telemetry (shown/opened/copied/
  not-helpful). Parity with VS Code 0.12.0.

---

## [0.5.0] – 2026-07-12 — *"Honest metrics"*

### Changed
- **Honest metric labels.** Recall counts now say whether they are *this
  month* (limited tiers) or *all-time* (unlimited); token/cost figures are
  labeled as estimates with their basis (~1,200 tok per reused lesson); the
  ROI section is now "Value estimate" with per-row explanations, and
  time-to-first-recall became "Time to first payoff" (Brain creation → first
  reused lesson), hidden when there is no data.
- **Solo-Brain team state** — a single-author Brain no longer shows a
  misleading "0.0% knowledge reuse"; it explains that cross-author metrics
  appear once a teammate joins.
- **Theme-aware colors** — the offline banner uses `JBColor` (light + dark)
  instead of hardcoded dark-only hex values; lesson text no longer uses a
  fixed `#444` that was unreadable in dark themes.
- Lessons dialog is now titled "Lessons" and says "showing the N
  most-recalled" instead of claiming to be "All Lessons" while rendering a
  server-capped subset. Lesson authors are shown when attributed.
- Brain Health lessons table is read-only (cells were editable before).
- API key field in Settings is masked (`JPasswordField`).

### Removed
- **Hourly `/recall` heartbeat pings.** The status bar widget used to POST
  `/recall` on install and every hour, inflating the recall counter that all
  ROI metrics are derived from. Recalls are now only counted when an AI
  actually reuses a lesson.
- The "IQ Boost" percentage from the status bar.

---

## [0.4.0] – 2026-07-06

### Added
- **Proactive briefing on project open** — the plugin now fetches your Brain's
  top lessons on project open and surfaces them as a notification (read-side
  "ambient recall" — the push counterpart to "Show Lessons"). Fires only when
  the Brain has lessons; opt-out via Settings → Cachly Brain → "Proactive
  briefing on project open". All network I/O is off the EDT and fail-safe.
  (Ambient Recall Tier C.)
- **Cross-harness rules files** — "Set Up AI Files" now also writes
  `.cursor/rules/cachly.mdc` (Cursor, with `alwaysApply` frontmatter),
  `.windsurfrules` (Windsurf) and `.clinerules` (Cline) alongside CLAUDE.md /
  AGENTS.md / copilot-instructions.md, so the Brain protocol reaches every AI
  assistant. (Ambient Recall Tier A.)

---

## [0.3.3] – 2026-06-20

### Fixed
- **Settings panel crash on IntelliJ 2024.1** — the plugin was compiled with a
  Java 21 target while IntelliJ 2024.1 (sinceBuild 241) runs on Java 17, so
  `CachlySettingsConfigurable` threw `UnsupportedClassVersionError` (class file
  version 65.0) and the Settings panel could not be instantiated. The plugin now
  targets Java 17, which runs across the entire declared 2024.1–2025.2 range.

---

## [0.3.2] – 2026-06-06

### Added
- **CI auto-detection** — "Set Up AI Files" now detects whether your `origin`
  remote is GitHub or GitLab and scaffolds the matching CI config: a GitHub
  Actions workflow (`.github/workflows/cachly.yml`) or a `.gitlab-ci.yml`
  include using the new GitLab CI/CD template. Idempotent and non-destructive.

---

## [0.3.1] – 2026-06-05

### Changed
- Version bump to align with MCP server 0.10.103 and VS Code extension 0.9.6.
- `brain_confirm_ci` tool now available in MCP server — CI self-calibration closes the feedback loop automatically.

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
