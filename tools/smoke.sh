#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"; mkdir -p "$dir/logs"
printf 'src/Main.java:12: warning: deprecated\n' >"$dir/logs/build.log"
printf 'src/Main.java:20: error: cannot find symbol\n' >>"$dir/logs/build.log"
printf "src/app.ts(5,7): warning - unused\n" >>"$dir/logs/build.log"
printf 'src/mod.py:3: [C0114(missing-module-docstring), ] Missing\n' >>"$dir/logs/build.log"
printf '{"pattern": "logs/*.log", "maxErrors": "5"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" errors)" = "1" ] || fail "errors: $(cat "$dir/output.json")"
[ "$(output "$dir" warnings)" = "3" ] || fail "warnings: $(cat "$dir/output.json")"
[ -f "$dir/out/warnings/index.html" ] || fail "report"

dir="$(mktemp -d)"; printf 'A.java:1: error: boom\n' >"$dir/build.log"
printf '{"pattern": "build.log"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "one error must fail the default gate"
grep -q "error(s)" "$dir/output.json" || fail "message"

echo "smoke ok: javac/eslint/pylint parsing, counts, gate"
