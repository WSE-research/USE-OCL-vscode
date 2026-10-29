#!/usr/bin/env bash
#
# build.sh -- builds the VS Code extension for USE: language server (Java 21,
# Maven), npm packages and the package use-ocl-<version>.vsix.
#
# Usage:  ./build.sh          build (Maven runs the unit tests in java/src/test)
#         ./build.sh test     build and test: grammars, language server with
#                             local Java and in the container, VS Code itself
#                             (if code and xvfb-run are available)
#
# Requires JDK 21 (JAVA_HOME), Maven and Node.js. The USE libraries come from
# USE_HOME (folder with lib/use-core-7.5.0.jar) or from the image use-ocl:7.5.0
# (docker build -t use-ocl:7.5.0 container, or run-use.sh from the SE exercises).

set -euo pipefail

IMAGE="use-ocl:7.5.0"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# Don't sync build output via Dropbox (only takes effect where Dropbox runs)
for d in node_modules server java/target .build; do
  mkdir -p "$d"
  python3 -c 'import os, sys; os.setxattr(sys.argv[1], "user.com.dropbox.ignored", b"1")' "$d" 2>/dev/null || true
done

javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
version=$("$javac" -version 2>&1 | sed -nE 's/^javac ([0-9]+).*/\1/p' || true)
if [[ -z $version ]] || (( version < 21 )); then
  echo "Benötigt ein JDK 21 oder neuer (JAVA_HOME setzen); gefunden: ${version:-keins}." >&2
  exit 1
fi

engine=${USE_ENGINE:-docker}
command -v "$engine" >/dev/null 2>&1 || engine=podman

if [[ -z ${USE_HOME:-} ]]; then
  USE_HOME="$HERE/.build/use"
  if [[ ! -f $USE_HOME/lib/use-core-7.5.0.jar ]]; then
    if ! "$engine" image inspect "$IMAGE" >/dev/null 2>&1; then
      echo "Das Image $IMAGE fehlt: $engine build -t $IMAGE container (oder USE_HOME setzen)." >&2
      exit 1
    fi
    echo "Hole die USE-Bibliotheken aus dem Image $IMAGE ..." >&2
    cid=$("$engine" create "$IMAGE")
    mkdir -p "$USE_HOME"
    "$engine" cp "$cid:/opt/use/lib" "$USE_HOME/lib"
    "$engine" cp "$cid:/opt/use/oclextensions" "$USE_HOME/oclextensions"
    "$engine" rm "$cid" >/dev/null
  fi
fi
export USE_HOME

echo "Sprachserver ..." >&2
(cd java && mvn -q -B package)
rm -rf server/*
cp java/target/use-lsp.jar java/target/lib/*.jar server/

# In the SE exercises, use-docker/ next to this folder holds the Dockerfile for
# run-use.sh; both must build the same image (comments may differ)
if [[ -f ../use-docker/Dockerfile ]] \
  && ! diff -q <(grep -v '^#' ../use-docker/Dockerfile) <(grep -v '^#' container/Dockerfile) >/dev/null; then
  echo "Warnung: container/Dockerfile weicht von ../use-docker/Dockerfile ab." >&2
fi

echo "Erweiterung ..." >&2
npm ci --no-audit --no-fund --loglevel=error
ext_version=$(node -p 'require("./package.json").version')
npx vsce package --out "use-ocl-$ext_version.vsix"

if [[ ${1:-} == test ]]; then
  echo "Test: Grammatiken" >&2
  npm run -s test:grammar
  echo "Test: Sprachserver mit lokalem Java" >&2
  node test/server-test.js
  if "$engine" image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "Test: Sprachserver im Container ($engine)" >&2
    USE_ENGINE=$engine node test/server-test.js --container
  else
    echo "Test im Container übersprungen: Image $IMAGE fehlt." >&2
  fi
  if command -v code >/dev/null 2>&1 && command -v xvfb-run >/dev/null 2>&1; then
    echo "Test: VS Code (unsichtbar mit xvfb-run)" >&2
    node test/e2e/run.js
    node test/e2e/run.js --java
  else
    echo "Test in VS Code übersprungen: code oder xvfb-run fehlt." >&2
  fi
fi
