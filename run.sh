#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
find src -name '*.java' > sources.txt
javac -d out @sources.txt
java -cp out com.deployflow.web.DeployFlowApp "${1:-8080}"
