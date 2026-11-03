#!/usr/bin/env bash
set -euo pipefail
: "${C8RUN_HOME:?Set C8RUN_HOME to an already unpacked Camunda 8 Run distribution}"
if [[ ! -x "$C8RUN_HOME/c8run" ]]; then
  echo 'Expected executable $C8RUN_HOME/c8run; adjust C8RUN_HOME for your distribution.' >&2
  exit 1
fi
export TZ=UTC
# Real-time minute proof: controlled clock jumps stall the RDBMS search exporter.
export ZEEBE_CLOCK_CONTROLLED=false
# Applies to JVMs started by c8run. Engine/search memory needs are separate from the client.
# Override if the selected distribution requires more heap. No download or unpack here.
export JAVA_TOOL_OPTIONS="${C8RUN_JAVA_TOOL_OPTIONS:--Xms128m -Xmx512m -XX:ActiveProcessorCount=2 -Duser.timezone=UTC}"
cd "$C8RUN_HOME"
exec ./c8run start "$@"
