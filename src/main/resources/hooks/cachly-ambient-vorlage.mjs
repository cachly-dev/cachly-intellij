#!/usr/bin/env node
// cachly Ambient Recall — __CACHLY_EREIGNIS__ v5
// Pushes relevant memory into context automatically. Cross-platform Node hook
// (no shell script — runs identically on Windows/macOS/Linux). Never blocks
// the agent: every failure path exits 0 with no output (graceful degrade).
// Runs the hook bundle next to this file (cachly-ambient-einblendung.mjs) in this process.
process.env.CACHLY_BRAIN_INSTANCE_ID = '__CACHLY_INSTANZ__';
process.env.CACHLY_HOOK_EVENT = '__CACHLY_EREIGNIS__';
process.argv.splice(2, process.argv.length, '__CACHLY_EREIGNIS__');
try {
  await import('./cachly-ambient-einblendung.mjs');
} catch {
  process.exit(0);
}
