#!/usr/bin/env bash
# End-to-end: selection flash, -pev dry-run and apply, replace glass, gmask, always-preview (typing, Enter executes), brush,
# typing -pev / --pev, carving, FAWE magic select, dry-run expiry
. "$(dirname "$0")/lib.sh"
cmd "gamemode creative $NAME"
cmd "time set noon"
cmd "gamerule doDaylightCycle false"
# Arena: flat world, ground at y=-61 (grass); clear space, 7x7 stone in the middle
for y in $(seq -60 -50); do cmd "fill -12 $y -12 12 $y 12 air"; done
cmd "fill -12 -61 -12 12 -61 12 grass_block"
cmd "fill -3 -60 -3 3 -60 3 stone"
look 0 -60 -14 0 -58 0
chat "/pev always off"
chat "/pev style auto"
chat "/pev select on"
chat "/pev carve on"
chat "/pev clear"
chat "//gmask"

# 1. Selection (no outlines or boxes: lines are left to other plugins) and the lime-glass selection flash
chat "//desel"
chat "//sel cuboid"
chat "//pos1 -3,-60,-3"
chat "//pos2 3,-56,3"
# 7x5x7 = 245, exposed shell = 245 - 5x3x5 = 170
ok select_flash 'waitfor "[ \"\$(dbg source)\" = SELECTION ] && [ \"\$(dbg glass)\" = 170 ]" 3'
ok no_entities '[ "$(dbg entities)" = 0 ]'
ok select_world_untouched 'isblock 0 -58 0 air && isblock 0 -60 0 stone'
shot 01-select-flash
ok select_flash_ends 'waitfor "[ \"\$(dbg source)\" = NONE ]" 6'
# independent of always-preview: can be turned off on its own
chat "/pev select off"
chat "//pos2 3,-55,3"
sleep 1
ok select_off '[ "$(dbg source)" = NONE ]'
chat "/pev select on"
chat "//pos2 3,-56,3"
ok select_flash_again 'waitfor "[ \"\$(dbg source)\" = SELECTION ]" 3'
ok select_flash_ends2 'waitfor "[ \"\$(dbg source)\" = NONE ]" 6'

# 2. -pev: dry-run only, shows the resulting blocks (not glass)
chat "//set oak_planks -pev 60"
ok pev_source 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8'
ok pev_blocks '[ "$(dbg fake)" -gt 0 ] && [ "$(dbg glass)" = 0 ]'
ok pev_world_untouched 'isblock 0 -58 0 air && isblock 0 -60 0 stone'
ok pev_no_lines '[ "$(dbg entities)" = 0 ]'
# carving: the line of sight goes through the preview, so part of it is carved out (the full preview stays the same)
ok carve 'waitfor "[ \"\$(dbg cut)\" -gt 0 ]" 3'
shot 02-pev-set
chat "/pev carve off"
ok carve_off 'waitfor "[ \"\$(dbg cut)\" = 0 ]" 3 && [ "$(dbg fake)" -gt 0 ]'
chat "/pev carve on"
ok carve_on 'waitfor "[ \"\$(dbg cut)\" -gt 0 ]" 3'

# 3. Apply
chat "/pev apply"
ok apply_written 'waitfor "isblock 3 -56 3 oak_planks" 6 && isblock 0 -60 0 oak_planks'
ok apply_cleared '[ "$(dbg source)" = NONE ]'
# 4. One //undo restores everything (the dry-run left no history)
chat "//undo"
ok undo_once 'waitfor "isblock 0 -58 0 air" 6 && isblock 0 -60 0 stone'

# 5. replace: lime glass (only the 49 stone blocks)
chat "//replace stone gold_block --pev 60"
ok replace_glass 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && [ "$(dbg glass)" = 49 ]'
ok replace_untouched 'isblock 0 -60 0 stone'
shot 03-replace-glass
chat "/pev clear"

# 6. gmask: //set only touches gmask matches, shown as glass
chat "//gmask stone"
chat "//set gold_block -pev 60"
ok gmask_glass 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && [ "$(dbg glass)" = 49 ]'
chat "/pev clear"

# 7. Always-preview: typing //set (respects gmask: only 49 blocks, real blocks shown)
chat "/pev always on"
typeonly "//set glowstone"
ok live_set_gmask 'waitfor "[ \"\$(dbg source)\" = LIVE ]" 5 && [ "$(dbg fake)" = 49 ] && [ "$(dbg glass)" = 0 ]'
esc
chat "//gmask"
typeonly "//set glowstone"
ok live_set 'waitfor "[ \"\$(dbg fake)\" -gt 49 ]" 5 && [ "$(dbg glass)" = 0 ]'
shot 04-live-set
esc
# 8. Typing //replace: glass while typing the mask, real blocks once the pattern is typed
typeonly "//replace stone"
ok live_mask 'waitfor "[ \"\$(dbg glass)\" = 49 ]" 5'
shot 05-live-mask
typemore " diamond_block"
ok live_pattern 'waitfor "[ \"\$(dbg glass)\" = 0 ] && [ \"\$(dbg fake)\" = 49 ]" 5'
shot 06-live-pattern
# Enter without -pev executes for real (no dry-run) and the live preview goes away
xdotool key Return
ok always_enter_executes 'waitfor "isblock 0 -60 0 diamond_block" 8'
ok always_enter_cleared 'waitfor "[ \"\$(dbg source)\" = NONE ] && [ \"\$(dbg armed)\" = false ]" 5'
chat "//undo"
ok always_undo 'waitfor "isblock 0 -60 0 stone" 6'
# with -pev it still only dry-runs
chat "//set glowstone -pev 60"
ok always_pev_dryrun 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && isblock 0 -60 0 stone && isblock 0 -58 0 air'
chat "/pev clear"

# 8b. The two typing stages can be turned off separately (/pev live mask|pattern)
chat "/pev live pattern off"
typeonly "//replace stone diamond_block"
ok live_pattern_off_mask 'waitfor "[ \"\$(dbg source)\" = LIVE ] && [ \"\$(dbg glass)\" = 49 ]" 5'
esc
typeonly "//set glowstone"
ok live_pattern_off_set 'sleep 1.5; [ "$(dbg source)" = NONE ]'
esc
chat "/pev live pattern on"
chat "/pev live mask off"
typeonly "//replace stone"
ok live_mask_off 'sleep 1.5; [ "$(dbg source)" = NONE ]'
typemore " diamond_block"
ok live_mask_off_pattern 'waitfor "[ \"\$(dbg source)\" = LIVE ] && [ \"\$(dbg glass)\" = 0 ] && [ \"\$(dbg fake)\" = 49 ]" 5'
esc
chat "/pev live mask on"
# 8c. Patterns registered by other plugins through WorldEdit's pattern factory (here: FAWE's #linear)
if grep -aq 'Preview enabled (FastAsyncWorldEdit)' "$LOG"; then
  typeonly "//set #linear[gold_block,diamond_block]"
  ok live_custom_pattern 'waitfor "[ \"\$(dbg source)\" = LIVE ] && [ \"\$(dbg fake)\" -gt 49 ]" 5 && [ "$(dbg glass)" = 0 ]'
  shot 10-custom-pattern
  esc
fi

# 9. Brush: aiming previews (nothing written), a real stroke writes as usual
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

# 10. Without always-preview, typing -pev already previews
chat "//desel"
chat "//sel cuboid"
chat "//pos1 -3,-60,-3"
chat "//pos2 3,-56,3"
typeonly "//set glowstone --pev"
ok typed_pev_set 'waitfor "[ \"\$(dbg source)\" = LIVE ] && [ \"\$(dbg fake)\" -gt 0 ]" 5 && [ "$(dbg glass)" = 0 ]'
esc
chat "/pev clear"
# Other commands (//walls): dry-run once in capture mode after typing pauses
typeonly "//walls glowstone -pev"
ok typed_pev_walls 'waitfor "[ \"\$(dbg source)\" = LIVE ] && [ \"\$(dbg fake)\" -ge 100 ]" 8 && [ "$(dbg armed)" = false ]'
ok typed_pev_untouched 'isblock -3 -58 0 air && isblock 0 -58 -3 air'
shot 09-typed-walls
# Deleting -pev clears the preview
xdotool key BackSpace BackSpace BackSpace BackSpace BackSpace
ok typed_pev_removed 'waitfor "[ \"\$(dbg source)\" = NONE ]" 5'
esc
ok typed_pev_untouched2 'isblock -3 -58 0 air'
chat "/pev clear"

# 11. FAWE magic select (//sel fuzzy): selects the 49 connected stone blocks
if grep -aq 'Preview enabled (FastAsyncWorldEdit)' "$LOG"; then
  look 0 -60 -14 0 -58 0
  chat "//sel fuzzy"
  chat "//pos1 0,-60,0"
  chat "//set gold_block -pev 60"
  ok fuzzy_pev 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && [ "$(dbg fake)" = 49 ] && isblock 0 -60 0 stone'
  shot 08-fuzzy
  chat "/pev clear"
  chat "/pev always on"
  typeonly "//set glowstone"
  ok fuzzy_live 'waitfor "[ \"\$(dbg source)\" = LIVE ] && [ \"\$(dbg fake)\" = 49 ]" 5'
  esc
  chat "/pev always off"
  chat "//sel cuboid"
fi

# 12. Dry-run previews expire: default dry-run-timeout (5 s), per command -pev N, -pev 0 = until cleared
chat "//pos1 -3,-60,-3"
chat "//pos2 3,-56,3"
chat "//set oak_planks --pev"
ok expire_start 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && lastis "//set oak_planks"'
T0=$(date +%s)
ok expire_default 'waitfor "[ \"\$(dbg source)\" = NONE ]" 8 && [ $(( $(date +%s) - T0 )) -ge 3 ] && [ "$(dbg last)" = null ]'
ok expire_untouched 'isblock 0 -58 0 air && isblock 0 -60 0 stone'
chat "/pev apply"
ok expire_no_apply 'sleep 1; isblock 0 -58 0 air'
chat "//set oak_planks -pev 12"
ok expire_n_start 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8 && lastis "//set oak_planks"'
T0=$(date +%s)
ok expire_n_kept 'sleep 8; [ "$(dbg source)" = COMMAND ]'
ok expire_n_cleared 'waitfor "[ \"\$(dbg source)\" = NONE ]" 8 && [ $(( $(date +%s) - T0 )) -ge 10 ]'
chat "//set oak_planks --pev 0"
ok expire_zero_start 'waitfor "[ \"\$(dbg source)\" = COMMAND ]" 8'
ok expire_zero_kept 'sleep 8; [ "$(dbg source)" = COMMAND ] && isblock 0 -58 0 air'
chat "/pev apply"
ok expire_zero_apply 'waitfor "isblock 0 -58 0 oak_planks" 6'
chat "//undo"
ok expire_zero_undo 'waitfor "isblock 0 -58 0 air" 6'
done_
