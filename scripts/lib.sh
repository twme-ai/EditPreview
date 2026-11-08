# Shared e2e helpers: drive the client on Xvfb :98 with xdotool, check the world and preview state from the console
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
S=$P/scripts
PASS=0; FAIL=0
cmd(){ $S/cmd.sh "$*"; sleep 0.15; }
# sync: wait until the client has processed all packets so far
SYNCN=0
sync(){
  SYNCN=$((SYNCN+1)); local m="SYNC_${RANDOM}_$SYNCN"
  $S/cmd.sh "tellraw $NAME \"$m\""
  for i in $(seq 200); do grep -aq "$m" "$CLOG" && break; sleep 0.05; done
  sleep 0.2
}
# chat: send one command and make sure the server received it
chat(){
  for try in 1 2 3; do
    local n0=$(grep -acF "issued server command: $1" "$LOG")
    sync; xdotool key t; sleep 0.6; xdotool type --delay 25 "$1"; sleep 0.2; xdotool key Return
    for i in $(seq 40); do [ $(grep -acF "issued server command: $1" "$LOG") -gt $n0 ] && { sleep 0.3; return 0; }; sleep 0.1; done
    echo "chat retry $try: $1" >&2; xdotool key Escape; sleep 1
  done
}
# typeonly: open chat and type without sending (typing preview)
typeonly(){ sync; xdotool key t; sleep 0.6; xdotool type --delay 40 "$1"; }
typemore(){ xdotool type --delay 40 "$1"; }
esc(){ xdotool key Escape; sleep 0.4; }
# dbg FIELD: read one field of pev debug
dbg(){
  local n0=$(grep -ac PEV_DEBUG "$LOG")
  $S/cmd.sh "pev debug $NAME"
  for i in $(seq 40); do [ $(grep -ac PEV_DEBUG "$LOG") -gt $n0 ] && break; sleep 0.05; done
  grep -a PEV_DEBUG "$LOG" | tail -1 | grep -oE "$1=[^ ]*" | cut -d= -f2-
}
# dbgline: print the whole pev debug line (used on failures)
dbgline(){ dbg source >/dev/null; echo "  $(grep -a PEV_DEBUG "$LOG" | tail -1 | sed "s/.*PEV_DEBUG //")"; }
# isblock X Y Z ID: is that block in the world ID
isblock(){
  local n0=$(grep -acE 'Test (passed|failed)' "$LOG")
  $S/cmd.sh "execute if block $1 $2 $3 $4"
  for i in $(seq 40); do [ $(grep -acE 'Test (passed|failed)' "$LOG") -gt $n0 ] && break; sleep 0.05; done
  grep -aE 'Test (passed|failed)' "$LOG" | tail -1 | grep -q passed
}
# waitfor 'CONDITION' [SECONDS]: poll until the condition holds
waitfor(){ local n=$(( ${2:-5} * 4 )); for i in $(seq $n); do eval "$1" && return 0; sleep 0.25; done; return 1; }
ok(){ if eval "$2"; then PASS=$((PASS+1)); echo "PASS_$1"; else FAIL=$((FAIL+1)); echo "FAIL_$1  ($2)"; dbgline; fi; }
look(){ # look FX FY FZ TX TY TZ: feet at F, looking at T
  read YAW PITCH < <(python3 -c "
import math,sys
fx,fy,fz,tx,ty,tz=map(float,sys.argv[1:7])
dx,dy,dz=tx-fx,ty-(fy+1.62),tz-fz
print(f'{-math.degrees(math.atan2(dx,dz)):.3f} {-math.degrees(math.atan2(dy,math.hypot(dx,dz))):.3f}')" "$@")
  cmd "tp $NAME $1 $2 $3 $YAW $PITCH"; sync
}
shot(){ mkdir -p "$P/.work/shots"; sleep 0.6; local W=$(xdotool search --name Minecraft | head -1); eval $(xdotool getwindowgeometry --shell $W); ffmpeg -v error -y -f x11grab -video_size ${WIDTH}x${HEIGHT} -i :98+$X,$Y -frames:v 1 "$P/.work/shots/$1.png"; }
[ -n "$(dbg source)" ] || { echo "FAIL_setup: EditPreview is not loaded (no reply to pev debug)"; exit 1; }
done_(){ echo "RESULT pass=$PASS fail=$FAIL"; }
