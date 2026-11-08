#!/usr/bin/env bash
# Deploy the jar from build/libs and restart the test server and client
. "$(dirname "$0")/env.sh"
cp "$P"/build/libs/EditPreview-*.jar "$SRV/plugins/EditPreview.jar" || exit 1
pkill -f '^pevclient'
"$P/scripts/cmd.sh" stop
for i in $(seq 60); do pgrep -f '^pevserver' >/dev/null || break; sleep 1; done
pkill -9 -f '^pevserver'; pkill -f '^tail -n \+1 -f console.in'
"$P/scripts/server.sh" | tail -2
"$P/scripts/client.sh" | tail -1
