# Changelog – Cachly Brain IntelliJ Plugin

---

## [0.9.1] – 2026-10-20 — *"Junie gets the project rule too"*

### Fixed
- **Junie now knows to call the Brain.** "Set Up AI Files" wrote the MCP entry for Junie (`.junie/mcp/mcp.json`) but no rule, so Junie had the cachly tools and no reason to use them. Now the command also writes `.junie/guidelines.md` with the marked Brain block (recall before each task, learn after each fix). Text of your own in that file stays; our block is appended once and updated in place on later runs. A write error on this file does not stop the other files.
- **One text everywhere.** The Brain block in the project files is now word for word the block that `npx @cachly-dev/mcp-server init` writes. A test reads the MCP source and fails on any difference.

## [0.9.0] – 2026-10-19 — *"Connect AI Assistant also writes the project rule"*

### Fixed
- **The AI Assistant chat now knows to call the Brain.** Until 0.8.9 "Connect AI Assistant" added the MCP server but wrote no project rule, so the chat had the cachly tools and no reason to use them (no `smart_recall` call from any JetBrains account up to 19.10.2026). Now the command also writes `.aiassistant/rules/cachly.md` in the project, with the same text as the other integrations (recall before each task, learn after each fix).
- A file with that name that is not ours (no cachly markers) is never overwritten; the notice tells you. Our own file is updated in place. If writing fails, the MCP connection still goes through and the notice names the problem.

## [0.8.9] – 2026-10-15 — *"The plugin asks first, and your key leaves the settings file"*

### Security
- **Nothing is sent before you answer.** On the first project start a dialog asks two questions: **Use the Brain** (file briefings and every call to the Brain; ticked) and **Share usage statistics** (event names; not ticked). Closing the dialog counts as "no" to both. Until you answer, the plugin sends no file path, no lesson and no event, and it does not fetch a trial key. You can change both answers in Settings → Tools → Cachly Brain; the question does not come back. If you update from 0.8.8, the dialog appears at the next project start and the status bar shows "Cachly: off" until you answer.
- **Your API key moves into the password safe of your IDE.** Until 0.8.8 it sat in plain text in `cachly-brain.xml`; the password field in the settings only hid it on screen. On the first start of 0.8.9 the plugin copies a stored key into the password safe, reads it back and compares it, and only then removes it from the file. If the read-back fails, the old value stays in the file and the next start tries again. You keep your access either way. If the password safe cannot hold a key (locked, not writable, or set to forget passwords on restart), the key stays in the settings file as before, so it is never lost. If the password safe already holds a different key, that one wins and is never overwritten.

### Fixed
- **Saving the settings no longer resets hidden state.** Until 0.8.8 every save rebuilt the settings from the form and dropped the trial end date, the onboarding counters and the "don't show again" switches.

- **The question cannot be answered by accident.** It has no default button: Enter does nothing, only a click on "Save my choice" or "Send nothing" answers it. In a real IDE the first version was confirmed within seconds by a keystroke meant for another window.

### Changed
- **The status bar updates right after you say yes**, instead of showing "Cachly: off" until the next 5-minute tick.
- **The status bar asks the server only while the IDE is the active window.** Until 0.8.8 it asked every 5 minutes, also with the IDE in the background.
- **Actions ask first.** Run Brain Doctor, Link Account, Connect Brain, Show Brain Health, Show Lessons and Save Lesson show the question if it is still open. If you said no, they tell you where to switch the Brain on instead of failing silently.
- The README and the Marketplace description name what is sent and link the privacy policy (cachly.dev/legal/datenschutz).

## [0.8.8] – 2026-10-14 — *"Link Account keeps your lessons"*

### Fixed
- **Link Account now moves your test Brain into your account, with its lessons.** Until 0.8.7 the button signed you in and then connected the account's own Brain: a new, empty one. The lessons of the test Brain stayed behind, and the test key kept working. Now the plugin does what `npx @cachly-dev/mcp-server claim` does:
  1. You sign in at auth.cachly.dev in the browser and confirm the code shown in the IDE.
  2. The plugin sends that sign-in together with the test key to cachly. The test Brain moves into your account.
  3. Every test key is revoked. The plugin stores the new key from your account, and `~/.cachly/credentials.json` too if it held the test key.
  4. The message says "Lessons kept" only after the new key has reached the Brain.
  5. The Brain restarts once with a new password (about 20 seconds); the status bar checks again after that.
- If moving is not possible (for example, your account already has a free Brain), the message gives the reason and nothing is changed.

### Added
- **Tools → Link Account.** Until now the button existed only in the trial notification and was gone once the notification closed.

## [0.8.7] – 2026-10-12 — *"The key stays out of the project, always"*

### Security
- **"Set Up AI Files" no longer writes your API key into the project, in any case.** 0.8.4 moved the key to `~/.cachly/credentials.json`, but kept one fallback: if that file already held a different key, the plugin wrote your key into `.mcp.json`, `.junie/mcp/mcp.json` and the `.claude/hooks/` scripts after all, files that `git add .` picks up and that end up on GitHub in public repositories. That fallback is gone. Now the plugin asks which key should be used for all projects, with two choices: **Use this key for all projects (replaces the stored one)** or **Keep the stored key**. Either way, no key goes into a project file. The same holds if `~/.cachly/credentials.json` cannot be written; the dialog then points you to `CACHLY_JWT` in your environment.
- **Old keys are cleaned out of existing projects.** If an older version left your key in `.mcp.json`, `.junie/mcp/mcp.json` or a hook script, "Set Up AI Files" removes it as soon as the key is stored in `~/.cachly/credentials.json`, and the final dialog lists the files. While a different key is stored there, those files stay as they are, so the project keeps working; they are listed too, and choosing "Use this key for all projects" cleans them in the same run.

## [0.8.6] – 2026-10-12 — *"The AI Assistant steps match the IDE"*

Found in a real run on IntelliJ IDEA 2025.3 (build 253) with AI Assistant 253.

### Fixed
- **The AI Assistant hint now names the steps that work.** 0.8.5 promised an "Enable Server" message from the IDE. It never appeared. What works: open Tools → AI Assistant → Model Context Protocol (MCP), tick cachly, press OK. On Windows, AI Assistant notices a changed `mcp.json` only after an IDE restart, so if cachly is missing in the list, restart the IDE once. The first start can take ~40 s (measured: 34 to 39 s with an empty npx cache). The hint also mentions the option "Automatically enable new and changed MCP servers" on that page, and the Open MCP settings button stays.
- **The hint stays readable.** The notification group now writes to the Event Log, so the hint is still there after a click or a timeout.
- **A custom `llm.mcp.client.global.mcp.json.path` works.** An absolute value was appended to the home folder, so the file landed where AI Assistant never reads, cachly was not found there, and the offer came back at every start. The plugin now resolves the path the way AI Assistant does: an absolute value wins.
- **The Brain Health window shows the overview, not its HTML source.** The header line read `<html> <h2>Brain Overview</h2> <table ...` as plain text. The text was built with `trimIndent()` after multi-line parts were inserted, which left spaces in front of `<html>`, and Swing renders HTML only when the text starts with `<html>`. This was not specific to IDE 2025.3. The same construction in the Show Lessons window (lessons with a multi-line "what worked") and in the file warning window is fixed too.

### Changed
- `ProactiveRecallStartup` and `FrameworkDetectionStartup` now use `ProjectActivity` instead of the old `StartupActivity`. IDE 253 logged "Migrate to ProjectActivity", and onboarding waited behind open dialogs. Behaviour is the same.

## [0.8.5] – 2026-10-12 — *"AI Assistant gets the Brain tools"*

### Fixed
- **JetBrains AI Assistant can now use your Brain.** AI Assistant reads MCP servers only from its own settings, not from `.mcp.json` or `.junie/mcp/mcp.json`. So its chat never had a cachly tool, even with a connected Brain. After connecting, the plugin now offers to connect AI Assistant:
  - **IDE 2025.3 and newer:** one click adds cachly to `~/.ai/mcp/mcp.json`, the file AI Assistant reads. AI Assistant then asks you to enable the server; that click stays yours. Other servers in the file stay untouched, and the file is only written when the cachly entry is missing or different.
  - **IDE 2024.1 to 2025.2:** the plugin copies the server config to the clipboard and opens Settings | Tools | AI Assistant | Model Context Protocol (MCP). Click Add, paste it, then OK and Apply.
  - On Windows the server starts through `cmd /c npx`, because the IDE starts it without a shell.
  - The API key stays out of the config when `~/.cachly/credentials.json` holds it.
- The offer appears at most once per IDE session, only when AI Assistant is installed and cachly is missing there. "Don't show again" and quiet mode turn it off. **Tools → Connect JetBrains AI Assistant** does the same at any time.

## [0.8.4] – 2026-10-11 — *"JetBrains AI Assistant reads the Brain protocol"*

### Security
- **Your API key stays out of the project.** Until 0.8.3, "Set Up AI Files" wrote the key in plain text into `.mcp.json`, `.junie/mcp/mcp.json` and the `.claude/hooks/` scripts, files that often end up in git. It now stores the key in `~/.cachly/credentials.json`, where the cachly MCP server already looks for it, and leaves it out of project files. If that file already holds a different key, it stays untouched and the plugin keeps the old behaviour, so the server never talks to the wrong account. Run "Set Up AI Files" once to clean existing projects.

### Added
- **A project rule for JetBrains AI Assistant.** "Set Up AI Files" now also writes `.aiassistant/rules/cachly.md`. AI Assistant has no hooks, but it adds project rules to every chat session by default, so it asks the Brain before a task and stores a lesson after a fix.

### Fixed
- **Your use of the Brain is counted.** Until 0.8.3 the plugin reported only "file briefing shown". On project open with a working Brain it now reports `session_start`, and after a saved lesson `learn_from_attempts`, the same events the MCP server and the VS Code extension send, with the plugin version. The project-open check runs even when the briefing is turned off; the briefing itself still follows your setting.

## [0.8.3] – 2026-10-11 — *"The new key is used right away"*

### Fixed
- **The first request now happens right after the trial starts.** Measured on 2026-10-11: of 10 trial keys created by 0.8.x, 6 were never used for a single request. The status bar asked 2 s after the window opened, before the key existed, and then only every 300 s. Now the plugin makes one request with the new key immediately, shows the result in the status bar and says in a notification whether the Brain answered. If it did not, the notification says so and points to Brain Doctor.
- **A missing instance id is fetched.** Without an instance id the plugin sent no request at all and the status bar said "not configured" forever. The plugin now asks `/api/v1/instances/auto` for it, after the trial, after linking an account, on project open and in "Set Up AI Files". Linking an account also no longer keeps the trial's instance id, which belonged to a different account.
- **The "How to connect your AI" link works.** It pointed to `cachly.dev/docs/jetbrains`, which did not exist (HTTP 404). The page now exists.

### Added
- **"Set Up AI Files" also writes `.junie/mcp/mcp.json`.** That is the project-level MCP file Junie reads, so Junie gets the Brain the same way Claude Code does through `.mcp.json`. JetBrains AI Assistant reads MCP servers from its settings only; the guide shows how to add the server there.

---

## [0.8.2] – 2026-09-03 — *"The version now comes from the build, not the platform"*

### Fixed
- **Release no longer blocked by an internal API.** The plugin read its own version number to build the `User-Agent` header. Both earlier ways of asking the platform for it turned out to be internal API: `PluginManagerCore.getPlugin(PluginId)` in 0.8.0 and `PluginManager.getPluginByClass(Class)` in 0.8.1. The Marketplace verifier rejected both, while our own `verifyPlugin` reported "Compatible" every time — it checks 2024.1 and 2025.2, the Marketplace checks the next EAP. The version is now written into the package by the build itself, so nothing is asked of the platform at runtime and the number cannot drift away from the shipped release.

---

## [0.8.1] – 2026-08-22 — *"First attempt at the version lookup"*

### Fixed
- Swapped `PluginManagerCore.getPlugin(PluginId)` for `PluginManager.getPluginByClass(Class)`. Verified compatible against 2024.1 and 2025.2; the Marketplace verifier found the replacement to be internal API as well, which 0.8.2 resolves for good.

---

## [0.8.0] – 2026-08-17 — *"The plugin can finally get you a key"*

### Added
- **First start now gets you a brain by itself.** Until this release the plugin
  offered exactly one way in: an empty API-key field in Settings. If you had no
  key, it fetched health once, saw "not healthy", and returned in silence —
  nothing asked you to sign up and nothing showed you where. Measured on
  2026-08-17: 18 Marketplace downloads, zero registrations. The plugin now does
  what the VS Code extension has done for weeks:
  1. asks the API for a free trial brain — no account, no credit card, no key to
     paste;
  2. falls back to browser approval (device flow) if you already have an account;
  3. and if both are unreachable, says so on screen with buttons for the
     dashboard and the settings. There is no longer a path that ends in silence.
- **"Connect Brain" in the Tools menu.** Reachable on purpose, not only as a
  side effect of opening a project — for anyone who dismissed the prompt, changed
  machines, or cleared their settings.
- **Screenshots on the Marketplace page.** Three promo images had been sitting in
  the repository since 2026-08-11, publicly reachable, and linked from nowhere.
  A page selling a visual tool showed not a single picture of it.

### Fixed
- **The plugin now says who it is.** Every request carries a
  `cachly-intellij/<version>` User-Agent. It sent none before, so nothing on the
  server side could tell whether the plugin had ever called the API at all —
  the one measurement that would have shown the missing onboarding months
  earlier.
- **Stale version in the README.** The build section named
  `cachly-brain-0.2.0.zip` while the plugin was at 0.7.2. The filename no longer
  carries a hand-typed number.

---

## [0.7.2] – 2026-08-13 — *"Marketplace housekeeping, no code change"*

### Fixed
- **License claim corrected.** The README said "MIT" in the License section while
  showing an Apache-2.0 badge at the top; the plugin actually ships under the
  Elastic License 2.0, the same license as the rest of the cachly repository. Both
  now say Elastic License 2.0, and a copy of the license file lives in this folder
  too, so the mirrored public repo has something to link to instead of a broken
  `../../LICENSE` path.
- **Release notes no longer go stale.** `changeNotes` in `build.gradle.kts` was a
  hand-copied string that fell out of sync with every release — 0.7.1 shipped with
  no notes at all because nobody remembered to update both places. The build now
  reads the topmost section of this file and turns it into the notes shown on the
  Marketplace, so the two cannot drift apart again.
- **Compatibility check now actually runs before publishing.** `verifyPlugin` was
  configured but only wired into a workflow GitHub never runs from this repo.
  `intellij-publish.yml` now runs it as a required step before `publishPlugin`, so
  an incompatible build blocks the release instead of reaching the Marketplace.

### Added
- **Dark-theme plugin icon** (`pluginIcon_dark.svg`) — same mark as the existing
  light icon, in colors readable on a dark IDE background.

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
