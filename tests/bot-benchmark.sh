#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
bash build.sh
javac --release 20 -encoding UTF-8 -cp 'lib/*:build/classes' -d "$classes" tests/BotBehaviorTest.java tests/BotTacticalTest.java tests/BotBenchmark.java
BOT_TACTICAL=true BOT_COUNT=0 BOT_AUTO_JOIN=false java -Xmx512m -cp "lib/*:build/classes:$classes" mobiarmy.server.BotBenchmark
