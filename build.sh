#!/usr/bin/env bash

set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

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

echo "Built dist/MobiArmy.jar"
echo "Run: java -jar dist/MobiArmy.jar"
