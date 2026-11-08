#!/usr/bin/env bash
# shot.sh 名稱：擷取客戶端畫面到 .work/shots/名稱.png
. "$(dirname "$0")/env.sh"
mkdir -p "$P/.work/shots"
W=$(timeout 5 xdotool search --name Minecraft | head -1)
[ -n "$W" ] || { echo "沒有 Minecraft 視窗"; exit 1; }
eval $(timeout 5 xdotool getwindowgeometry --shell $W)
ffmpeg -v error -y -f x11grab -video_size ${WIDTH}x${HEIGHT} -i :98+$X,$Y -frames:v 1 "$P/.work/shots/$1.png"
echo "$P/.work/shots/$1.png"
