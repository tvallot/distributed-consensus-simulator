#!/usr/bin/env bash
# Compiles the project and runs it.
#   ./run.sh [nodes]        interactive simulation (default: 5 nodes)
#   ./run.sh check [runs]   headless runs with random crashes, checks the consensus properties
set -euo pipefail
cd "$(dirname "$0")"

CP="lib/jbotsim-standalone-1.2.0.jar"
rm -rf build
javac --release 17 -nowarn -sourcepath src -d build -cp "$CP" src/consensus/*.java

if [[ "${1:-}" == "check" ]]; then
    shift
    java -cp "build:$CP" consensus.BatchCheck "$@"
else
    java -cp "build:$CP" consensus.Main "$@"
fi
