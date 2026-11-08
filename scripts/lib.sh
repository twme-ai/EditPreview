# e2e 共用：xdotool 操作 Xvfb :98 上的客戶端，主控台檢查世界與預覽狀態
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"
S=$P/scripts
PASS=0; FAIL=0
cmd(){ $S/cmd.sh "$*"; sleep 0.15; }
# sync：等客戶端處理完目前為止的封包
SYNCN=0
sync(){
  SYNCN=$((SYNCN+1)); local m="SYNC_${RANDOM}_$SYNCN"
  $S/cmd.sh "tellraw $NAME \"$m\""
  for i in $(seq 200); do grep -aq "$m" "$CLOG" && break; sleep 0.05; done
  sleep 0.2
}
# chat：送出一行指令，確認伺服器收到
chat(){
  for try in 1 2 3; do
    local n0=$(grep -acF "issued server command: $1" "$LOG")
    sync; xdotool key t; sleep 0.6; xdotool type --delay 25 "$1"; sleep 0.2; xdotool key Return
    for i in $(seq 40); do [ $(grep -acF "issued server command: $1" "$LOG") -gt $n0 ] && { sleep 0.3; return 0; }; sleep 0.1; done
    echo "chat retry $try: $1" >&2; xdotool key Escape; sleep 1
  done
}
# typeonly：打開聊天欄打字但不送出（打字預覽）
typeonly(){ sync; xdotool key t; sleep 0.6; xdotool type --delay 40 "$1"; }
typemore(){ xdotool type --delay 40 "$1"; }
esc(){ xdotool key Escape; sleep 0.4; }
# dbg 欄位：讀 /pev debug 的某個欄位
dbg(){
  local n0=$(grep -ac PEV_DEBUG "$LOG")
  $S/cmd.sh "pev debug $NAME"
  for i in $(seq 40); do [ $(grep -ac PEV_DEBUG "$LOG") -gt $n0 ] && break; sleep 0.05; done
  grep -a PEV_DEBUG "$LOG" | tail -1 | grep -oE "$1=[^ ]*" | cut -d= -f2-
}
# isblock X Y Z ID：世界上那格是不是 ID
isblock(){
  local n0=$(grep -acE 'Test (passed|failed)' "$LOG")
  $S/cmd.sh "execute if block $1 $2 $3 $4"
  for i in $(seq 40); do [ $(grep -acE 'Test (passed|failed)' "$LOG") -gt $n0 ] && break; sleep 0.05; done
  grep -aE 'Test (passed|failed)' "$LOG" | tail -1 | grep -q passed
}
# waitfor '條件' [秒]：輪詢到條件成立
waitfor(){ local n=$(( ${2:-5} * 4 )); for i in $(seq $n); do eval "$1" && return 0; sleep 0.25; done; return 1; }
ok(){ if eval "$2"; then PASS=$((PASS+1)); echo "PASS_$1"; else FAIL=$((FAIL+1)); echo "FAIL_$1  ($2)"; fi; }
look(){ # look FX FY FZ TX TY TZ：腳站在 F，看向 T
  read YAW PITCH < <(python3 -c "
import math,sys
fx,fy,fz,tx,ty,tz=map(float,sys.argv[1:7])
dx,dy,dz=tx-fx,ty-(fy+1.62),tz-fz
print(f'{-math.degrees(math.atan2(dx,dz)):.3f} {-math.degrees(math.atan2(dy,math.hypot(dx,dz))):.3f}')" "$@")
  cmd "tp $NAME $1 $2 $3 $YAW $PITCH"; sync
}
shot(){ mkdir -p "$P/.work/shots"; sleep 0.6; local W=$(xdotool search --name Minecraft | head -1); eval $(xdotool getwindowgeometry --shell $W); ffmpeg -v error -y -f x11grab -video_size ${WIDTH}x${HEIGHT} -i :98+$X,$Y -frames:v 1 "$P/.work/shots/$1.png"; }
[ -n "$(dbg source)" ] || { echo "FAIL_setup: EditPreview 沒有載入（pev debug 沒有回應）"; exit 1; }
done_(){ echo "RESULT pass=$PASS fail=$FAIL"; }
