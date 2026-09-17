#!/usr/bin/env bash

set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

action="${1:-run}"
mysql_container="${MYSQL_CONTAINER:-mobiarmy-mysql}"
game_port="${MOBIARMY_PORT:-8122}"
admin_host="${ADMIN_HOST:-127.0.0.1}"
admin_port="${ADMIN_PORT:-8080}"
admin_username="${ADMIN_USERNAME:-admin}"
admin_password="${ADMIN_PASSWORD:-admin123}"
db_url="${DB_URL:-jdbc:mysql://127.0.0.1:3306/army?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}"
db_user="${DB_USER:-root}"
db_password="${DB_PASSWORD:-}"
use_docker_db="${USE_DOCKER_DB:-true}"

usage() {
  cat <<'EOF'
Usage:
  ./run-manual.sh              Build và chạy server + web admin
  ./run-manual.sh build        Chỉ build JAR
  ./run-manual.sh run-no-build Chạy mà không build lại

Biến môi trường thường dùng:
  ADMIN_USERNAME, ADMIN_PASSWORD, ADMIN_HOST, ADMIN_PORT
  MOBIARMY_PORT
  DB_URL, DB_USER, DB_PASSWORD
  USE_DOCKER_DB=false          Dùng MySQL bên ngoài thay vì container
  MYSQL_CONTAINER             Tên container MySQL, mặc định mobiarmy-mysql
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Thiếu lệnh bắt buộc: $1" >&2
    exit 1
  fi
}

check_port_free() {
  local port="$1"
  local service="$2"
  if command -v ss >/dev/null 2>&1 && ss -ltnH "sport = :$port" 2>/dev/null | grep -q .; then
    echo "$service không thể chạy vì cổng $port đang được sử dụng." >&2
    exit 1
  fi
}

build_server() {
  require_command javac
  require_command jar

  local java_major
  java_major="$(javac -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
  if [[ ! "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 21 )); then
    echo "Cần JDK 21 trở lên, hiện tại: $(javac -version 2>&1)" >&2
    exit 1
  fi

  echo "[1/3] Build MobiArmy.jar"
  rm -rf build/classes
  mkdir -p build/classes dist/lib
  find src -name '*.java' -print > build/sources.txt
  javac --release 20 -encoding UTF-8 \
    -cp 'lib/*' \
    -d build/classes \
    @build/sources.txt
  cp lib/*.jar dist/lib/
  jar --create \
    --file dist/MobiArmy.jar \
    --manifest manifest.mf \
    -C build/classes .
  echo "Đã build: dist/MobiArmy.jar"
}

start_docker_mysql() {
  require_command docker

  if docker container inspect "$mysql_container" >/dev/null 2>&1; then
    if [[ "$(docker inspect -f '{{.State.Running}}' "$mysql_container")" != "true" ]]; then
      echo "[2/3] Khởi động MySQL container: $mysql_container"
      docker start "$mysql_container" >/dev/null
    else
      echo "[2/3] MySQL container đang chạy: $mysql_container"
    fi
  else
    echo "[2/3] Tạo MySQL container và import army.sql"
    docker run -d \
      --name "$mysql_container" \
      --restart unless-stopped \
      -e MYSQL_ALLOW_EMPTY_PASSWORD=yes \
      -e MYSQL_DATABASE=army \
      -p 127.0.0.1:3306:3306 \
      -v "$project_dir/army.sql:/docker-entrypoint-initdb.d/001-army.sql:ro" \
      mysql:8 \
      --character-set-server=utf8mb4 \
      --collation-server=utf8mb4_unicode_ci \
      --restrict-fk-on-non-standard-key=OFF >/dev/null
  fi

  echo "Đợi MySQL sẵn sàng..."
  local attempt
  for attempt in $(seq 1 60); do
    if docker exec "$mysql_container" mysql -uroot -Darmy \
      -e 'SELECT 1 FROM caption LIMIT 1' >/dev/null 2>&1; then
      echo "MySQL đã sẵn sàng."
      return
    fi
    sleep 1
  done

  echo "MySQL không sẵn sàng sau 60 giây. Kiểm tra bằng:" >&2
  echo "  docker logs $mysql_container" >&2
  exit 1
}

case "$action" in
  run|build|run-no-build)
    ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac

if [[ "$action" != "run-no-build" ]]; then
  build_server
fi

if [[ "$action" == "build" ]]; then
  exit 0
fi

require_command java
check_port_free "$game_port" "Game server"
check_port_free "$admin_port" "Web admin"

if [[ "$use_docker_db" == "true" ]]; then
  start_docker_mysql
else
  echo "[2/3] Bỏ qua Docker MySQL; sử dụng DB_URL=$db_url"
fi

echo "[3/3] Khởi động MobiArmy server"
echo "Game server: 0.0.0.0:$game_port"
echo "Web admin : http://$admin_host:$admin_port"
echo "Admin user: $admin_username"
if [[ "$admin_password" == "admin123" ]]; then
  echo "CẢNH BÁO: đang dùng mật khẩu development admin123."
fi
echo "Nhấn Ctrl+C để dừng game server và web admin."

export MOBIARMY_HEADLESS=true
export MOBIARMY_PORT="$game_port"
export ADMIN_ENABLED=true
export ADMIN_HOST="$admin_host"
export ADMIN_PORT="$admin_port"
export ADMIN_USERNAME="$admin_username"
export ADMIN_PASSWORD="$admin_password"
export DB_URL="$db_url"
export DB_USER="$db_user"
export DB_PASSWORD="$db_password"

exec java -jar dist/MobiArmy.jar
