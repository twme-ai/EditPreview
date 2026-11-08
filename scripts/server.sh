#!/usr/bin/env bash
# Start the test server: console commands are written to console.in
. "$(dirname "$0")/env.sh"
cd "$SRV"
rm -f console.in; mkfifo console.in
: > out.log
# Process names deliberately differ from other test setups that pkill "^tail -f console.in" or "^java.*client.main.Main"
setsid nohup bash -c 'tail -n +1 -f console.in | exec -a pevserver java -Xmx2G -jar paper.jar --nogui >> out.log 2>&1' >/dev/null 2>&1 < /dev/null &
for i in $(seq 180); do grep -q 'Done (' out.log 2>/dev/null && break; sleep 1; done
grep -a 'Done (\|EditPreview\]' out.log | tail -4
