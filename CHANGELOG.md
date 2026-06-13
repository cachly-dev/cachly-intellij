# Changelog – Cachly Brain IntelliJ Plugin

---

## [0.3.3] – 2026-06-13

### Added
- **Cost per avoided call** — the Brain Health dialog's ROI Summary now shows the
  per-call price used for savings estimates, read from the instance's configured
  `cost_per_call_usd` (falls back to the $0.002 default), so the dollar figures
  reflect your real bill.
- **Week-over-week activity** — a new ROI row compares this week's Brain activity
  to the prior week (computed client-side from the insights 30-day trend) with a
  ▲/▼ indicator and percentage change. Shows a "no baseline yet" hint until two
  weeks of data exist.

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
