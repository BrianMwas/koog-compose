# koog-compose

Kotlin Multiplatform (Android / desktop / iOS) Compose library wrapping the koog
AI agent engine. Core lives in `koog-compose-core`; supporting modules:
`-ui`, `-device`, `-mediapipe`, `-session-room`, `-testing`; sample apps in
`composeApp` and `sample-app`.

## Build / test commands
- Fast compile gate (no Android SDK needed): `./gradlew :koog-compose-core:compileKotlinDesktop -q`
- Testing module gate: `./gradlew :koog-compose-testing:compileKotlinDesktop -q`
- Full gate: `./gradlew :koog-compose-core:desktopTest`
- Android / iOS (full toolchain): `:koog-compose-core:compileDebugKotlinAndroid`,
  `:koog-compose-core:compileKotlinIosSimulatorArm64`

## koog 1.0.0 migration (active)
- Authoritative plan: **KOOG_1.0.0_MIGRATION_AUDIT.md** — read it before editing.
- koog version lives in `gradle/libs.versions.toml` (`koog = "..."`).
- Rules:
  - Do NOT guess koog 1.0.0 symbol names. After each edit, recompile and let the
    compiler report the real name; only then change the import/call. A PostToolUse
    hook (`.claude/hooks/kt-compile.sh`) compiles on every `.kt` edit and feeds the
    error back on failure.
  - Keep edits scoped to one file/area at a time; recompile between areas.
  - The `prompt { }` DSL stays in `ai.koog.prompt.dsl`; only the `Prompt` *type*
    moved to `ai.koog.prompt.Prompt`.
  - koog 1.0.0 requires **JDK 17**.

## Conventions
- All `commonMain` code must compile for every target — prefer `kotlin.time.Clock`
  / `kotlin.time.Instant` over the deprecated `kotlinx.datetime` equivalents.
