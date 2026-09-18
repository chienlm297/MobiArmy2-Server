#!/usr/bin/env bash
# Creates and removes its own disposable database; never uses the production volume.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
project="army-priority-test-${RANDOM}-$$"
override=$(mktemp)
java_classes=$(mktemp -d)
export TEST_ADMIN_PORT="${TEST_ADMIN_PORT:-18080}"
export TEST_GAME_PORT="${TEST_GAME_PORT:-18122}"
export ADMIN_USERNAME=admin ADMIN_PASSWORD=admin123
cat > "$override" <<EOF
services:
  server:
    environment:
      BOT_COUNT: "3"
      BOT_TARGET_MODE: RANDOM
      BOT_AUTO_JOIN: "false"
    ports: !override
      - "127.0.0.1:${TEST_GAME_PORT}:8122"
      - "127.0.0.1:${TEST_ADMIN_PORT}:8080"
EOF
compose=(docker compose -p "$project" -f docker-compose.yml -f "$override")
cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then "${compose[@]}" logs --tail 80 server; fi
  "${compose[@]}" down -v --remove-orphans
  rm -f "$override"
  rm -rf "$java_classes"
}
trap cleanup EXIT
bash build.sh
javac --release 20 -encoding UTF-8 -cp 'lib/*:build/classes' -d "$java_classes" tests/AdminRoomsTest.java tests/AdminBootstrapBroadcastTest.java
java -cp "lib/*:build/classes:$java_classes" mobiarmy.admin.AdminRoomsTest
"${compose[@]}" up -d --build
export TEST_DB_CONTAINER
TEST_DB_CONTAINER=$("${compose[@]}" ps -q db)
python3 - <<'PY'
import http.client, os, time
for attempt in range(60):
    try:
        c = http.client.HTTPConnection('127.0.0.1', int(os.environ['TEST_ADMIN_PORT']), timeout=2)
        c.request('GET', '/login')
        if c.getresponse().status == 200:
            break
    except OSError:
        pass
    finally:
        c.close()
    time.sleep(1)
else:
    raise SystemExit('Admin did not become ready')
PY
python3 tests/admin_priority.py

if [ "${TEST_BROWSER:-0}" = 1 ]; then
  python3 tests/admin_browser.py
fi

# Exercise a pre-upgrade DB with no admin_account; separate from the HTTP fixture DB.
docker exec "$TEST_DB_CONTAINER" mysql -uroot -e 'CREATE DATABASE admin_migration_test; CREATE TABLE admin_migration_test.admin_audit_log LIKE army.admin_audit_log;'
server_container=$("${compose[@]}" ps -q server)
docker cp "$java_classes/." "$server_container:/tmp/admin-test-classes"
docker exec -e 'DB_URL=jdbc:mysql://db:3306/admin_migration_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' "$server_container" java -cp 'MobiArmy.jar:lib/*:/tmp/admin-test-classes' mobiarmy.admin.AdminBootstrapBroadcastTest
