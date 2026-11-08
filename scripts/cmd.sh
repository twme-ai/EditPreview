#!/usr/bin/env bash
# Send one console command (gives up after 5 s if the server is not running)
. "$(dirname "$0")/env.sh"
timeout 5 bash -c 'echo "$1" > "$2"' _ "$*" "$SRV/console.in"
