#!/usr/bin/env bash
# Player settings survive a server crash: change every setting, kill -9 the server (no player-data save), start again
. "$(dirname "$0")/lib.sh"
F="$SRV/plugins/EditPreview/players"
chat "/pev always on"
chat "/pev brush off"
chat "/pev select off"
chat "/pev carve off"
chat "/pev live mask off"
chat "/pev live pattern off"
chat "/pev style glass"
ok settings_file 'ls "$F"/*.yml >/dev/null 2>&1 && grep -q "style: glass" "$F"/*.yml'

pkill -f '^pevclient'
pkill -9 -f '^pevserver'; pkill -f '^tail -n \+1 -f console.in'
sleep 2
"$S/server.sh" | tail -1
"$S/client.sh" | tail -1
sleep 5
ok persist_after_crash '[ "$(dbg always)" = true ] && [ "$(dbg brush)" = false ] && [ "$(dbg select)" = false ] && [ "$(dbg carve)" = false ] && [ "$(dbg livemask)" = false ] && [ "$(dbg livepattern)" = false ] && [ "$(dbg style)" = GLASS ]'

# back to the defaults the e2e expects
chat "/pev always off"
chat "/pev brush on"
chat "/pev select on"
chat "/pev carve on"
chat "/pev live mask on"
chat "/pev live pattern on"
chat "/pev style auto"
ok persist_restored '[ "$(dbg always)" = false ] && [ "$(dbg carve)" = true ] && [ "$(dbg style)" = AUTO ]'
done_
