# Using Claude Code (terminal) to run the koog 0.8.0 → 1.0.0 migration

A practical playbook for doing the migration yourself in the terminal with the
Claude Code CLI. The whole strategy hinges on one fact from
`KOOG_1.0.0_MIGRATION_AUDIT.md`: most items are marked **"Verify"** because only
the compiler knows the exact new koog symbols. So we set Claude Code up as a
**compiler-driven loop** — it edits, recompiles, reads the error, fixes — on your
machine where the build actually works.

> ⚠️ Flags evolve. Confirm anything here with `claude --help` and `/help` in your
> installed version before relying on it in a script.

---

## 0. The core idea: let the Kotlin compiler drive

The audit tells you *what categories* changed; `./gradlew compileKotlinDesktop`
tells you *the exact symbol*. If you wire Claude Code so that **every edit
triggers a recompile and feeds the error back**, it converges on the real 1.0.0
API without anyone guessing names. That mechanism is a **PostToolUse hook**
(Section 2). Set that up first — everything else is workflow around it.

---

## 1. One-time project setup

### a) Allowlist Gradle so it doesn't prompt on every build

`.claude/settings.local.json` (git-ignored by default — good for personal config):

```json
{
  "permissions": {
    "allow": [
      "Bash(./gradlew:*)",
      "Bash(git diff:*)",
      "Bash(git status)",
      "Bash(git log:*)"
    ]
  }
}
```

`Bash(./gradlew:*)` matches any `./gradlew …` invocation. Without this you'll
click "allow" hundreds of times during a compile loop.

### b) Put the migration contract in `CLAUDE.md`

Claude reads `CLAUDE.md` at the start of every session. Add this section so it
always knows the build commands and the rules of this job:

```markdown
## koog 1.0.0 migration (active)
- Authoritative plan: see KOOG_1.0.0_MIGRATION_AUDIT.md (read it before editing).
- koog version lives in gradle/libs.versions.toml (koog = "1.0.0").
- Fast compile gate (no Android SDK needed): ./gradlew :koog-compose-core:compileKotlinDesktop -q
- Full gate: ./gradlew :koog-compose-core:desktopTest
- Rules:
  - Do NOT guess koog 1.0.0 symbol names. After each edit, recompile and let the
    compiler tell you the real name; only then change the import/call.
  - Keep edits scoped to one file/area at a time; recompile between areas.
  - The `prompt { }` DSL stays in ai.koog.prompt.dsl; only the Prompt *type* moved.
```

### c) (Optional) SessionStart hook — fail fast if the tree is already broken

`.claude/settings.json`:

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [ { "type": "command", "command": ".claude/hooks/baseline.sh" } ] }
    ]
  }
}
```

`.claude/hooks/baseline.sh`:

```bash
#!/usr/bin/env bash
# Surface the current compile state to Claude at session start.
./gradlew :koog-compose-core:compileKotlinDesktop -q 2>&1 | tail -40 || true
```

---

## 2. The PostToolUse compile gate (the important one)

Run a fast desktop compile after every Kotlin edit, and **block (exit 2) with the
compiler output on stderr** so Claude reads it and self-corrects.

`.claude/settings.json`:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [ { "type": "command", "command": ".claude/hooks/kt-compile.sh" } ]
      }
    ]
  }
}
```

`.claude/hooks/kt-compile.sh`:

```bash
#!/usr/bin/env bash
out="$(./gradlew :koog-compose-core:compileKotlinDesktop -q 2>&1)"
if [ $? -ne 0 ]; then
  # Exit code 2 feeds stderr back to Claude as actionable context.
  echo "Desktop compile failed — fix using the REAL symbol from this output:" >&2
  echo "$out" | tail -60 >&2
  exit 2
fi
exit 0
```

```bash
chmod +x .claude/hooks/*.sh
```

Now the loop is: Claude edits → hook compiles → on failure Claude sees
`Unresolved reference: nodeLLMSendToolResult` (or whatever the real rename is) →
fixes it → recompiles. This is exactly how the "Verify" items in the audit get
resolved correctly.

> `compileKotlinDesktop` avoids the Android SDK and is the fastest gate. Use the
> full `desktopTest` only at checkpoints, not on every edit.

---

## 3. Run the migration in plan mode, then acceptEdits

Start a session, pull the audit into context, and plan before editing:

```bash
claude
```

Then in the prompt:

```
Read @KOOG_1.0.0_MIGRATION_AUDIT.md and @gradle/libs.versions.toml.
We're executing this migration in the audit's risk-ranked order (Section 4).
First do Section 2 only (build config: koog=1.0.0, JDK 17 in every build.gradle.kts,
explicit agents-features-memory dep). Plan it, don't edit yet.
```

- Press **Shift+Tab** to cycle into **plan mode** (it explores/reads but won't
  edit). Or launch with `claude --permission-mode plan`.
- Review the plan; approve it. Then **Shift+Tab** into **acceptEdits** so it
  applies edits and the compile hook runs automatically.

`@file` syntax inlines a file into the prompt — `@KOOG_1.0.0_MIGRATION_AUDIT.md`
is how you hand Claude the audit each session.

---

## 4. Drive it section-by-section (matches the audit's order)

Do these as separate prompts, recompiling between each. `/clear` between major
sections keeps context lean.

1. **Build config** — `koog = "1.0.0"`, `JVM_11→JVM_17` and `VERSION_11→VERSION_17`
   in every module, add `implementation(libs.koog.agents.memory)`. Then
   `./gradlew help` to confirm it configures.
2. **`PhasestrategyBuilder.kt`** (highest risk):
   > "Migrate the graph DSL node calls in
   > koog-compose-core/.../phase/PhasestrategyBuilder.kt to koog 1.0.0. Don't guess
   > names — after each change, rely on the compile hook output to find the real
   > node function and the new ReceivedToolResults type."
3. **`StreamingFeatureConfig.kt`** (pipeline rename `AIAgentPipeline`→`AIAgentPipelineAPI`).
4. **`PromptExecutor` subclasses** — `StructuredPhaseExecutor.kt` + testing's
   `FakePromptExecutor.kt`. Switch the gate to also compile testing:
   `./gradlew :koog-compose-testing:compileKotlinDesktop`.
5. **`KoogEventHandlerBridge.kt` + `PhaseAwareAgent.kt`** — and **write a test**
   that asserts an event reaches the sink (the audit notes `runCatching` would
   otherwise hide a silent break).
6. **`KoogaiProvider.kt`** — `Prompt`/`KoogClock` imports, `OllamaClient.baseUrl`.
7. **Tool bridge + structured output**, then the stray MCP import (audit §3.12).

A handy custom slash command, `.claude/commands/migrate.md`:

```markdown
---
description: Migrate one file/area to koog 1.0.0 using the compiler as ground truth
---
Migrate $ARGUMENTS to koog 1.0.0. Follow KOOG_1.0.0_MIGRATION_AUDIT.md.
Do not guess koog symbol names — edit, let the PostToolUse compile hook run, read
the error, and use the exact name it reports. Recompile until clean, then stop.
```

Invoke as `/migrate koog-compose-core/.../phase/PhasestrategyBuilder.kt`.

---

## 5. Parallelize the independent work with subagents

The **showcase samples** (audit §6) are independent of each other and of the core
fixes, so they're ideal to fan out. Define a subagent in
`.claude/agents/sample-author.md`:

```markdown
---
name: sample-author
description: Writes commonMain koog-compose showcase samples + a desktopTest for each
tools: Read, Edit, Write, Bash, Grep, Glob
---
You write small, self-contained koog-compose samples in commonMain that run without
an API key (use the FakePromptExecutor from koog-compose-testing). Each sample gets a
desktopTest. Follow the table in KOOG_1.0.0_MIGRATION_AUDIT.md §6. Compile with
./gradlew :koog-compose-core:desktopTest before finishing.
```

Then: *"Use the sample-author subagent to build the ParallelContextSample and
StreamingSample in parallel."* Each runs in its own context window and reports
back, so the core migration in your main session isn't polluted.

---

## 6. Headless mode for the verification gate / CI

Once it compiles, use non-interactive mode to run the full gate and summarize
failures — scriptable and CI-friendly:

```bash
claude -p "Run ./gradlew :koog-compose-core:desktopTest. If it fails, fix the failures \
using the audit, recompiling until green. Report what changed." \
  --allowedTools "Bash,Read,Edit" \
  --output-format json | jq -r '.result'
```

- `-p` / `--print` = headless. `--output-format` supports `text`, `json`,
  `stream-json`.
- For Android/iOS targets later (on a full toolchain): `compileDebugKotlinAndroid`,
  `compileKotlinIosSimulatorArm64`.

---

## 7. Span it across sessions

```bash
claude --continue     # resume the most recent session in this dir
claude --resume        # interactive picker of past sessions
```

Plan on day 1, implement on day 2, verify on day 3 — `--resume` keeps the context.
Use `/compact` before switching from planning to implementing to shrink history
while keeping the thread.

---

## 8. Caveats / safety

- **Don't** use `--permission-mode bypassPermissions` outside a throwaway
  container — it skips all prompts.
- The compile-hook-on-every-edit is great but slows large multi-file edits; if it
  drags, toggle hooks off via `/hooks` and compile manually at checkpoints.
- Keep `CLAUDE.md` short (< ~200 lines) — it loads every session.
- Commit per audit section (`feat: migrate graph DSL to koog 1.0.0`) so a bad area
  is easy to revert. Branch `claude/amazing-planck-3cwyox` backs PR #51, so each
  push updates it.

---

## TL;DR

Allowlist `./gradlew`, add a **PostToolUse hook that recompiles and returns errors
on exit 2**, put the build commands + "don't guess symbols" rule in `CLAUDE.md`,
then work through the audit's risk order in **plan → acceptEdits**, `@`-referencing
`KOOG_1.0.0_MIGRATION_AUDIT.md`, with a `sample-author` subagent fanning out the
samples. The compile hook is what makes the "Verify" items resolve to the *real*
koog 1.0.0 API instead of guesses.

---

### Quick reference

| Feature | Syntax | Use |
|---|---|---|
| Plan mode | `Shift+Tab` to cycle, or `claude --permission-mode plan` | Explore/plan before editing |
| Accept edits | `Shift+Tab` to cycle, or `--permission-mode acceptEdits` | Apply edits + run hooks |
| Inline a file | `@path/to/file` in a prompt | Hand Claude the audit |
| Headless | `claude -p "…" --output-format json` | Scripted/CI runs |
| Pre-approve tools | `--allowedTools "Bash,Read,Edit"` | Skip prompts in headless |
| Allowlist commands | `permissions.allow` in `.claude/settings.json` | No prompt on `./gradlew` |
| Hooks | `SessionStart`, `PostToolUse`, `Stop` in `.claude/settings.json` | Compile gate, baseline |
| Slash command | `.claude/commands/<name>.md`, `$ARGUMENTS` | `/migrate <file>` |
| Subagent | `.claude/agents/<name>.md` | Fan out samples |
| New/compact context | `/clear`, `/compact` | Keep context lean |
| Resume | `claude --continue`, `claude --resume` | Multi-day work |
| MCP | `claude mcp add <name> …`, `claude mcp list` | External tools |
