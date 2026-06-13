---
description: Migrate one file/area to koog 1.0.0 using the compiler as ground truth
---
Migrate $ARGUMENTS to koog 1.0.0. Follow KOOG_1.0.0_MIGRATION_AUDIT.md.

Do NOT guess koog symbol names. Edit, let the PostToolUse compile hook run
(`./gradlew :koog-compose-core:compileKotlinDesktop`), read the error, and use the
exact name/type the compiler reports. Recompile until the area is clean, then stop
and summarize what changed and which audit "Verify" items you resolved.
