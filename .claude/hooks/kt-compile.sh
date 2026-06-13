#!/usr/bin/env bash
# PostToolUse compile gate for the koog 1.0.0 migration.
#
# Runs a fast desktop compile of koog-compose-core after a Kotlin edit and, on
# failure, BLOCKS (exit 2) with the compiler output on stderr so Claude reads the
# REAL koog 1.0.0 symbol name and self-corrects — instead of guessing.
#
# Only fires for *.kt edits; edits to docs/config skip the compile.

# The tool-input JSON arrives on stdin; bail out cheaply for non-Kotlin edits.
payload="$(cat)"
if ! printf '%s' "$payload" | grep -q '\.kt"'; then
  exit 0
fi

out="$(./gradlew :koog-compose-core:compileKotlinDesktop -q 2>&1)"
status=$?

if [ $status -ne 0 ]; then
  {
    echo "Desktop compile failed — fix using the REAL symbol from this output"
    echo "(do NOT guess koog 1.0.0 names; use exactly what the compiler reports):"
    echo "$out" | tail -60
  } >&2
  exit 2
fi
exit 0
