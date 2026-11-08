#!/usr/bin/env bash
# 端對端：選區邊框、-pev 試跑與套用、replace 玻璃、gmask、恆預覽（打字、Enter 試跑）、筆刷
. "$(dirname "$0")/lib.sh"
cmd "gamemode creative $NAME"
cmd "time set noon"
cmd "gamerule doDaylightCycle false"
# 場地：平坦世界，地面 y=-61（草）；清出空間，中間放 7×7 石頭
for y in $(seq -60 -50); do cmd "fill -12 $y -12 12 $y 12 air"; done
cmd "fill -12 -61 -12 12 -61 12 grass_block"
cmd "fill -3 -60 -3 3 -60 3 stone"
look 0 -60 -14 0 -58 0
chat "/pev always off"
chat "/pev style auto"
chat "/pev clear"
chat "//gmask"

# 1. 選區邊框（7×5×7：12 條邊＋2 個角）
chat "//sel cuboid"
chat "//pos1 -3,-60,-3"
chat "//pos2 3,-56,3"
ok outline 'waitfor "[ \"\$(dbg outline)\" -ge 14 ]" 3'
shot 01-outline

# 2. -pev：只試跑，顯示寫入後的方塊（不是玻璃）
chat "//set oak_planks -pev"
ok pev_source 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8'
ok pev_blocks '[ "$(dbg fake)" -gt 0 ] && [ "$(dbg glass)" = 0 ]'
ok pev_world_untouched 'isblock 0 -58 0 air && isblock 0 -60 0 stone'
shot 02-pev-set

# 3. 套用
chat "/pev apply"
ok apply_written 'waitfor "isblock 3 -56 3 oak_planks" 6 && isblock 0 -60 0 oak_planks'
ok apply_cleared '[ "$(dbg source)" = NONE ]'
# 4. //undo 一次就回到原狀（試跑沒有留下歷史）
chat "//undo"
ok undo_once 'waitfor "isblock 0 -58 0 air" 6 && isblock 0 -60 0 stone'

# 5. replace：綠色玻璃（只有 49 格石頭）
chat "//replace stone gold_block -pev"
ok replace_glass 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && [ "$(dbg glass)" = 49 ]'
ok replace_untouched 'isblock 0 -60 0 stone'
shot 03-replace-glass
chat "/pev clear"

# 6. gmask：//set 也只動 gmask 符合的，且用玻璃
chat "//gmask stone"
chat "//set gold_block -pev"
ok gmask_glass 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && [ "$(dbg glass)" = 49 ]'
chat "/pev clear"

# 7. 恆預覽：打字預覽 //set（尊重 gmask：只有 49 格，顯示真的方塊）
chat "/pev always on"
typeonly "//set glowstone"
ok live_set_gmask 'waitfor "[ \"\$(dbg source)\" = LIVE ]" 5 && [ "$(dbg fake)" = 49 ] && [ "$(dbg glass)" = 0 ]'
esc
chat "//gmask"
typeonly "//set glowstone"
ok live_set 'waitfor "[ \"\$(dbg fake)\" -gt 49 ]" 5 && [ "$(dbg glass)" = 0 ]'
shot 04-live-set
esc
# 8. 打字預覽 //replace：打遮罩時是玻璃，打 pattern 時是真實預覽
typeonly "//replace stone"
ok live_mask 'waitfor "[ \"\$(dbg glass)\" = 49 ]" 5'
shot 05-live-mask
typemore " diamond_block"
ok live_pattern 'waitfor "[ \"\$(dbg glass)\" = 0 ] && [ \"\$(dbg fake)\" = 49 ]" 5'
shot 06-live-pattern
xdotool key Return
ok always_enter_preview 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && isblock 0 -60 0 stone'
chat "/pev apply"
ok always_apply 'waitfor "isblock 0 -60 0 diamond_block" 6'
chat "//undo"
ok always_undo 'waitfor "isblock 0 -60 0 stone" 6'

# 9. 筆刷：瞄準就預覽（不寫入），真的刷下去照常寫入
chat "//sel"
cmd "item replace entity $NAME weapon.mainhand with minecraft:blaze_rod"
sync
chat "//br sphere gold_block 2"
look 0 -60 -10 0 -61 -5
ok brush_preview 'waitfor "[ \"\$(dbg source)\" = BRUSH ] && [ \"\$(dbg fake)\" -gt 0 ]" 5'
ok brush_untouched 'isblock 0 -61 -5 grass_block'
shot 07-brush
xdotool click 3
ok brush_real 'waitfor "isblock 0 -61 -5 gold_block" 6'
chat "//undo"
ok brush_undo 'waitfor "isblock 0 -61 -5 grass_block" 6'
chat "/pev always off"
ok brush_off 'waitfor "[ \"\$(dbg source)\" = NONE ]" 3'
cmd "item replace entity $NAME weapon.mainhand with minecraft:air"
done_
