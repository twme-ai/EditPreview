#!/usr/bin/env bash
# 啟動測試伺服器：主控台指令寫進 console.in（FIFO）
. "$(dirname "$0")/env.sh"
cd "$SRV"
rm -f console.in; mkfifo console.in
: > out.log
# 行程名稱刻意和 CreativeKit 的 restart.sh（pkill "^tail -f console.in"、"^java.*client.main.Main"）對不上
setsid nohup bash -c 'tail -n +1 -f console.in | exec -a pevserver java -Xmx2G -jar paper.jar --nogui >> out.log 2>&1' >/dev/null 2>&1 < /dev/null &
for i in $(seq 180); do grep -q 'Done (' out.log 2>/dev/null && break; sleep 1; done
grep -a 'Done (\|EditPreview\]' out.log | tail -4
