#!/usr/bin/env bash
# SessionStart hook — surface the current desktop compile state to Claude so a
# session never starts blind to a tree that is already red. Never blocks.
./gradlew :koog-compose-core:compileKotlinDesktop -q 2>&1 | tail -40 || true
