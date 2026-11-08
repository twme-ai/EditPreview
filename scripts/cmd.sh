#!/usr/bin/env bash
# 送一行主控台指令（伺服器沒在跑時 5 秒後放棄）
. "$(dirname "$0")/env.sh"
timeout 5 bash -c 'echo "$1" > "$2"' _ "$*" "$SRV/console.in"
