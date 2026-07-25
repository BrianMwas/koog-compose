# koog-compose

Kotlin Multiplatform (Android / desktop / iOS) Compose library wrapping the koog
AI agent engine. Core lives in `koog-compose-core`; supporting modules:
`-ui`, `-device`, `-mediapipe`, `-session-room`, `-testing`; sample apps in
`composeApp` and `sample-app`.

## Build / test commands
- Fast compile gate (no Android *SDK* needed): `./gradlew :koog-compose-core:compileKotlinDesktop -q`
- Testing module gate: `./gradlew :koog-compose-testing:compileKotlinDesktop -q`
- Full gate: `./gradlew :koog-compose-core:desktopTest`
- Samples gate (keeps `sample-app/commonMain` honest): `./gradlew :sample-app:desktopTest`
- Android / iOS (full toolchain): `:koog-compose-core:compileDebugKotlinAndroid`,
  `:koog-compose-core:compileKotlinIosSimulatorArm64`

### Network requirement — every task above needs `dl.google.com`
"No Android SDK needed" is not the same as "no Android toolchain needed". The root
`build.gradle.kts` declares `com.android.library`, so **Gradle resolves AGP before it can
configure any project** — even a desktop-only task. AGP is published only to Google's Maven
(`maven.google.com` just 301s to `dl.google.com`); it is not on Maven Central or the plugin
portal. Compose Multiplatform desktop pulls `androidx.*` from the same host.

So in a sandbox where `dl.google.com` is blocked, **nothing in this repo can be configured**,
and the failure looks like a plugin-resolution error rather than a network error. If you hit
that, the only code that can still be typechecked is `koog-compose-core` (pure Kotlin, koog +
coroutines + serialization, all on Maven Central) via a throwaway `jvm()`-only KMP project
that `srcDir`s the real source folders. Compose code cannot be compiled at all.

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
