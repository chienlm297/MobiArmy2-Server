#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
bash build.sh
javac --release 20 -encoding UTF-8 -cp 'lib/*:build/classes' -d "$classes" tests/BotBehaviorTest.java tests/AdminBotsTest.java tests/BotTacticalTest.java tests/BotItemsTest.java tests/AdminBotPolicyTest.java tests/BotDeadTargetTest.java tests/BotStallTest.java
BOT_TACTICAL=true BOT_COUNT=0 BOT_AUTO_JOIN=false BOT_REQUIRE_HUMAN=true BOT_READY_DELAY_MS=2000 BOT_LEAVE_DELAY_MS=120000 BOT_TARGET_MODE=RANDOM \
  java -cp "lib/*:build/classes:$classes" mobiarmy.server.BotBehaviorTest

BOT_COUNT=0 java -cp "lib/*:build/classes:$classes" mobiarmy.admin.AdminBotsTest

BOT_COUNT=0 java -cp "lib/*:build/classes:$classes" mobiarmy.server.BotTacticalTest

BOT_COUNT=0 java -cp "lib/*:build/classes:$classes" mobiarmy.server.BotItemsTest

BOT_COUNT=0 java -cp "lib/*:build/classes:$classes" mobiarmy.admin.AdminBotPolicyTest

BOT_TACTICAL=false BOT_COUNT=0 java -cp "lib/*:build/classes:$classes" mobiarmy.server.BotDeadTargetTest

BOT_COUNT=0 java -cp "lib/*:build/classes:$classes" mobiarmy.server.BotStallTest
