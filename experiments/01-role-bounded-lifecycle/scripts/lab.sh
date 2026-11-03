#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# Heap is bounded; total RSS also includes threads, direct buffers, metadata and code.
exec java -Xms32m -Xmx192m -XX:ActiveProcessorCount=2 -Duser.timezone=UTC \
  -jar target/role-bounded-lifecycle.jar "$@"
