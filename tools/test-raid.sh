#!/bin/sh
# End-to-end test with Carpet's fake players on a running server with this mod, Carpet, mipack-testkit and a file named
# raidturns-debug in the game folder. Usage: tools/test-raid.sh <server folder>. Prints PASS/FAIL per case.
T=$1
c() { echo "$1" > "$T/in.fifo"; sleep "${2:-0.5}"; }
# ask "<command>" <reply prefix>: runs it and prints its reply
ask() {
  n=$(wc -l < "$T/console.log"); echo "$1" > "$T/in.fifo"; i=0
  while [ $i -lt 40 ]; do
    out=$(tail -n +$((n + 1)) "$T/console.log" | grep -iF "$2" | tail -1)
    [ -n "$out" ] && break; sleep 0.25; i=$((i + 1))
  done
  echo "${out#*]: }"
}
check() { if echo "$2" | grep -Eiq "$3"; then echo "PASS $1"; else echo "FAIL $1 -> $(echo "$2" | cut -c1-900)"; fi; }
state() { ask "raidturns state" "[raidturns] state"; }
log() { ask "raidturns log $1" "[raidturns] log"; }
# wait_state <regex> <max seconds>
wait_state() { i=0; while [ $i -lt "$2" ]; do s=$(state); echo "$s" | grep -Eiq "$1" && return 0; sleep 1; i=$((i + 1)); done; echo "WAIT TIMEOUT $1: $s" | cut -c1-500; return 1; }

X=0
c "raidturns nodamage true"
# raid <boss> <player:props>...: new crystal, everyone enters, nobody presses R yet
raid() {
  boss=$1; shift; X=$((X + 30)); Z=$X
  c "raidturns bossmove none"
  c "fill $((X - 3)) 199 -3 $((X + 12)) 199 6 minecraft:stone"; c "setblock $X 200 4 minecraft:air"
  c "raidturns crystal $X 200 4 $boss" 1
  n=0
  for spec in "$@"; do
    name=${spec%%:*}; props=${spec#*:}
    c "player $name kill" 0.3; c "player $name spawn at $((X - 2 + n * 2)) 200 0" 2
    # Carpet spawns them once the chunk is loaded
    i=0; until ask "mrtest give $name $props" "give $name" | grep -q equipo || [ $i -ge 30 ]; do sleep 1; i=$((i + 1)); done
    n=$((n + 1))
  done
  for spec in "$@"; do c "raidturns enter ${spec%%:*} $X 200 4" 1.5; done
}
endraid() { for p in "$@"; do c "player $p kill" 0.5; done; sleep 3; }
expected_blocked() { ask "raidturns enter $1 $X 200 4" "enter $1"; }

echo "--- 1, 2, 3: start together, one Pokémon, wait for everyone"
raid cobblemonraiddens:cubone rtA:jolteon\ level=100 rtB:snorlax\ level=100 rtC:blissey\ level=100
c "mrtest add rtA squirtle level=50"
c "player rtD kill" 0.3; c "player rtD spawn at $((X + 8)) 200 0" 2
i=0; until ask "mrtest give rtD eevee level=50" "give rtD" | grep -q equipo || [ $i -ge 30 ]; do sleep 1; i=$((i + 1)); done
c "raidturns r rtB"
check "countdown running" "$(state)" "COUNTDOWN"
check "closed during countdown" "$(expected_blocked rtD)" "blocked raidturns.closed"
wait_state "CHOOSING" 12
s=$(state)
check "everyone starts when one presses R" "$s" "RTA.*team=1.*"
check "one Pokémon per player (rtA has two)" "$s" "rtA must=true resp=0 team=1"
check "three copies" "$s" "rtc must"
check "one menu per player when choosing starts" "$(state)" "menus=3 "
c "mrtest act rtA default"; c "mrtest act rtB default"; sleep 2
check "round waits for the last one" "$(state)" "CHOOSING"
c "raidturns menu rtA" 0.5
check "someone who chose gets no new menu" "$(state)" "menus=3 "
c "raidturns menu rtC" 0.5
check "someone missing gets the menu again" "$(state)" "menus=4 "
c "mrtest act rtC default"; sleep 1
check "no menus while the moves run" "$(state)" "menus=4 "
wait_state "turn=3.*turn=3.*turn=3" 40 && echo "PASS round resolves when all chose"
wait_state "CHOOSING" 20
check "next round opens the menus again" "$(state)" "menus=7 "
s=$(state)
check "the boss bar gets the raid's health" "$s" "healthSent=[1-9]"
check "same boss move in all copies" "$(log rtA)" "\|move\|p2a"
a=$(log rtA | grep -Eo '\|move\|p2a: #\|[^|]+' | head -1); b=$(log rtB | grep -Eo '\|move\|p2a: #\|[^|]+' | head -1)
[ -n "$a" ] && [ "$a" = "$b" ] && echo "PASS boss used $a in both" || echo "FAIL boss moves differ: $a / $b"

echo "--- 5: order by priority and speed, boss in the list"
s=$(state); echo "$s" | grep -Eo 'order=\[[^]]*\]'
echo "$s" | python3 -c "
import re,sys
o=re.findall(r'(\w+):(-?\d+):(-?\d+)', re.search(r'order=\[([^]]*)\]',sys.stdin.read()).group(1))
k=[(int(p),int(v)) for _,p,v in o]
print('PASS sorted by priority, speed' if k==sorted(k,reverse=True) and any(n=='boss' for n,_,_ in o) else 'FAIL order %s'%o)"

echo "--- 7: end-of-turn effects once per round, shared sleep"
c "raidturns eval rtA battle.sides[0].active[0].setStatus('brn')" 1
c "mrtest act rtA default"; c "mrtest act rtB default"; c "mrtest act rtC default"
wait_state "turn=5.*turn=5.*turn=5" 40
n=$(log rtA | grep -o 'from. brn' | wc -l | tr -d ' ')
[ "$n" -le 2 ] && [ "$n" -ge 1 ] && echo "PASS burn once per round ($n lines: absolute and percent)" || echo "FAIL burn lines $n"
wait_state "CHOOSING" 20
c "raidturns eval rtA const p = battle.sides[0].active[0]; const m = battle.dex.moves.get('spore'); p.moveSlots[0] = {move: m.name, id: m.id, pp: 20, maxpp: 20, target: m.target, disabled: false, used: false}" 1
c "mrtest act rtA default"; c "mrtest act rtB default"; c "mrtest act rtC default"
# The default move is now Spore, and the boss sleeps at least 2 rounds
wait_state "turn=7.*turn=7.*turn=7" 40; wait_state "CHOOSING" 20
for p in rtA rtB rtC; do c "raidturns eval $p battle.sides[1].active[0].status + '=' + battle.sides[1].active[0].statusState.time" 1; done
ta=$(log rtA | grep -Eo '<<< .?slp=[0-9]+' | tail -1); tb=$(log rtB | grep -Eo '<<< .?slp=[0-9]+' | tail -1); tc=$(log rtC | grep -Eo '<<< .?slp=[0-9]+' | tail -1)
[ -n "$ta" ] && [ "$ta" = "$tb" ] && [ "$tb" = "$tc" ] && echo "PASS same sleep counter in all copies: $ta" || echo "FAIL sleep counters: $ta / $tb / $tc"
check "the boss sleeps in every copy" "$(log rtB)" "\|cant\|p2a: #\|slp"
check "everyone is shown the boss asleep, not only who used Spore" "$(state)" "rtA .*shown=slp.*rtB .*shown=slp.*rtC .*shown=slp"
check "the Spore user's lines reach the others" "$(state)" "relayed=[1-9]"

check "the copy's full health is hidden" "$(state)" "healthHidden=[1-9]"

echo "--- 3b: timeout uses the default move"
wait_state "CHOOSING" 10; c "mrtest act rtA default"; c "mrtest act rtB default"
sleep 40
check "still waiting after 40 s" "$(state)" "CHOOSING"
sleep 22
check "timeout resolved the round" "$(state)" "STEP|READING_SPEED|WAITING_TURN|turn=9.*turn=9"

echo "--- 4: no PP uses Struggle"
wait_state "CHOOSING" 40
c "raidturns eval rtB for (const m of battle.sides[0].active[0].moveSlots) m.pp = 0" 1
for round in 1 2; do
  c "mrtest act rtA default"; c "mrtest act rtB default"; c "mrtest act rtC default"; sleep 2
  wait_state "CHOOSING" 40
done
check "Struggle without PP" "$(log rtB)" "Struggle"

echo "--- 10: fleeing with company"
c "mrtest act rtC forfeit" 4
s=$(ask "raidturns inraid rtC" "inraid rtC")
check "out of the raid dimension and group" "$s" "dim=false participant=false"
check "cannot come back while it lasts" "$(expected_blocked rtC)" "blocked raidturns.banned"
check "the others keep going" "$(state)" "players=2"
wait_state "CHOOSING" 10

echo "--- 8: a fainted player has no more turns, gets rewards if the party wins"
c "raidturns eval rtA battle.sides[0].active[0].hp = 1" 1
c "mrtest act rtA default"; c "mrtest act rtB default"
sleep 3; wait_state "CHOOSING" 40
check "fainted leaves the round" "$(state)" "CHOOSING.*(RTB|rtB|rtb) must"
s=$(state); echo "$s" | grep -q "RTA must\|rtA must" && echo "FAIL fainted player still in round: $s" || echo "PASS fainted player out of the round"
check "still inside watching" "$(ask 'raidturns inraid rtA' 'inraid rtA')" "dim=true"
sleep 2
check "a fainted player spectates a teammate's copy" "$(state)" "rtB .*spectators=[1-9]"
c "raidturns rewards clear"; c "mrtest raidend rtB" 3
r=$(ask "raidturns rewards" "[raidturns] rewards"); echo "$r"
check "fainted player gets rewards" "$(ask 'raidturns inraid rtA' 'inraid rtA')" "rewards=true"
check "whoever fled gets none" "$(ask 'raidturns inraid rtC' 'inraid rtC')" "rewards=false"
endraid rtA rtB rtC rtD

echo "--- 8b: if everyone faints the raid is lost"
raid cobblemonraiddens:cubone rtE:eevee\ level=100
c "raidturns r rtE"; wait_state "CHOOSING" 12
c "raidturns rewards clear"
# The boss may miss or use a status move: repeats until it faints
i=0; while [ $i -lt 8 ] && state | grep -q CHOOSING; do
  c "raidturns eval rtE battle.sides[0].active[0].hp = 1" 1; c "mrtest act rtE default"; sleep 2; i=$((i + 1))
  wait_state "CHOOSING|no rounds" 40
done
check "raid lost" "$(ask 'raidturns inraid rtE' 'inraid rtE')" "rewards=false"
check "no round left" "$(state)" "no rounds"
endraid rtE

echo "--- 10b: fleeing alone lets you start again"
raid cobblemonraiddens:cubone rtF:eevee\ level=100
c "raidturns r rtF"; wait_state "CHOOSING" 12
c "mrtest act rtF forfeit" 5
check "out of the dimension" "$(ask 'raidturns inraid rtF' 'inraid rtF')" "dim=false"
sleep 3
check "can enter again" "$(ask "raidturns enter rtF $X 200 4" '[raidturns] enter')" "enter rtF host"
endraid rtF

echo "--- 9: disconnecting mid round"
raid cobblemonraiddens:cubone rtG:pikachu\ level=100 rtH:snorlax\ level=100
c "raidturns r rtG"; wait_state "CHOOSING" 12
c "mrtest act rtG default"; c "player rtH kill" 3
wait_state "turn=3" 30 && echo "PASS round goes on without the one who left"
endraid rtG

echo "--- 5b: priority goes before speed"
raid cobblemonraiddens:cubone rtJ:pikachu\ level=100 rtK:rattata\ level=8
c "raidturns r rtJ"; wait_state "CHOOSING" 12
c "mrtest act rtJ default"; c "raidturns act rtK quickattack" 1; sleep 2
s=$(state); echo "$s" | grep -Eo 'order=\[[^]]*\]'
check "Quick Attack first" "$s" "order=\[RTK:1:"
endraid rtJ rtK

echo "--- 6: a boost of the boss applies once"
raid cobblemonraiddens:cubone rtL:snorlax\ level=100 rtM:snorlax\ level=100
c "raidturns r rtL"; wait_state "CHOOSING" 12
for p in rtL rtM; do c "raidturns eval $p const b = battle.sides[1].active[0]; const m = battle.dex.moves.get('swordsdance'); b.moveSlots[0] = {move: m.name, id: m.id, pp: 20, maxpp: 20, target: m.target, disabled: false, used: false}" 1; done
c "mrtest act rtL default"; c "mrtest act rtM default"
wait_state "turn=3.*turn=3" 30; wait_state "CHOOSING" 20
c "raidturns bossmove swordsdance"
for p in rtL rtM; do c "raidturns eval $p 'atk=' + battle.sides[1].active[0].boosts.atk" 1; done
before=$(log rtL | grep -Eo 'atk=[0-9]+' | tail -1 | cut -d= -f2)
c "mrtest act rtL default"; c "mrtest act rtM default"; sleep 2
wait_state "CHOOSING" 40
for p in rtL rtM; do c "raidturns eval $p 'atk=' + battle.sides[1].active[0].boosts.atk" 1; done
for p in rtL rtM; do
  after=$(log $p | grep -Eo 'atk=[0-9]+' | tail -1 | cut -d= -f2)
  [ "$after" = "$((before + 2))" ] && echo "PASS boost +2 once in $p" || echo "FAIL boost in $p: $before -> $after"
done
endraid rtL rtM

echo "--- 6b: stat changes reach everyone"
raid cobblemonraiddens:cubone rtR:snorlax\ level=100 rtS:snorlax\ level=100
c "raidturns r rtR"; wait_state "CHOOSING" 12
c "raidturns eval rtR const p = battle.sides[0].active[0]; const m = battle.dex.moves.get('growl'); p.moveSlots[0] = {move: m.name, id: m.id, pp: 40, maxpp: 40, target: m.target, disabled: false, used: false}" 1
c "raidturns eval rtS const p = battle.sides[0].active[0]; const m = battle.dex.moves.get('swordsdance'); p.moveSlots[0] = {move: m.name, id: m.id, pp: 40, maxpp: 40, target: m.target, disabled: false, used: false}" 1
c "raidturns bossmove splash"
c "mrtest act rtR default"; c "mrtest act rtS default"; sleep 2
wait_state "turn=[3-9].*turn=[3-9]" 30; wait_state "CHOOSING" 20; sleep 1
for p in rtR rtS; do c "raidturns eval $p 'bossatk=' + battle.sides[1].active[0].boosts.atk" 1; done
br=$(log rtR | grep -Eo 'bossatk=-?[0-9]+' | tail -1); bs=$(log rtS | grep -Eo 'bossatk=-?[0-9]+' | tail -1)
[ "$br" = "bossatk=-1" ] && [ "$bs" = "bossatk=-1" ] && echo "PASS Growl from one player lowers the boss in every copy" || echo "FAIL boss atk: $br / $bs"
s=$(state)
check "the panel shows the boss's stat change to both" "$s" "rtR .*view=[^ /]*/[^ /]*/atk:-1.*rtS .*view=[^ /]*/[^ /]*/atk:-1"
check "the panel shows each player's own stat change" "$s" "rtS .*view=[^ /]*/atk:2/"
check "the stat change reaches the others' log" "$s" "boost"
endraid rtR rtS

echo "--- 14: with three players, turns advance one round at a time"
raid cobblemonraiddens:cubone rtT:snorlax\ level=100 rtU:snorlax\ level=100 rtV:snorlax\ level=100
c "raidturns r rtT"; wait_state "CHOOSING" 12
# Tackle: a move like Hyper Beam adds a legitimate round to recharge
for p in rtT rtU rtV; do c "raidturns eval $p const p = battle.sides[0].active[0]; const m = battle.dex.moves.get('tackle'); p.moveSlots[0] = {move: m.name, id: m.id, pp: 40, maxpp: 40, target: m.target, disabled: false, used: false}; p.moveSlots.length = 1" 0.5; done
sleep 8
s=$(state)
echo "$s" | grep -Eq 'CHOOSING.*rtT must=true resp=0.*turn=1 .*rtU must=true resp=0.*turn=1 .*rtV must=true resp=0.*turn=1 ' || echo "$s" | grep -Eiq 'CHOOSING.*rtT must=true resp=0.*turn=1 .*rtU must=true resp=0.*turn=1 .*rtV must=true resp=0.*turn=1 ' && echo "PASS nothing advances while nobody chooses" || echo "FAIL advanced without choices: $s" | cut -c1-900
for round in 1 2 3; do
  c "mrtest act rtT default"; c "mrtest act rtU default"; c "mrtest act rtV default"; sleep 2
  want=$((round * 2 + 1)); i=0
  until state | grep -Eiq "CHOOSING.*must=true.*must=true.*must=true" && [ "$(state | grep -Eo ' turn=[0-9]+' | sort -u | wc -l | tr -d ' ')" = 1 ] || [ $i -ge 40 ]; do sleep 1; i=$((i + 1)); done
  s=$(state); turns=$(echo "$s" | grep -Eo ' turn=[0-9]+' | sed 's/ //' | tr '\n' ' ')
  [ "$turns" = "turn=$want turn=$want turn=$want " ] && echo "PASS round $round: exactly two internal turns in all three copies" || echo "FAIL round $round: wanted turn=$want, got $turns"
  shown=$(echo "$s" | grep -Eo 'rounds=\[[0-9,]*\]' | tr '\n' ' ')
  list=$(seq 1 $((round + 1)) | paste -sd, -); [ "$shown" = "rounds=[$list] rounds=[$list] rounds=[$list] " ] && echo "PASS round $round: the log counts [$list] in all three copies" || echo "FAIL round $round: log shows $shown, wanted [$list]"
done
endraid rtT rtU rtV

echo "--- 12: alone"
raid cobblemonraiddens:cubone rtI:snorlax\ level=100
c "raidturns r rtI"; wait_state "CHOOSING" 12; c "mrtest act rtI default"
wait_state "turn=[3-9]" 30 && echo "PASS raid of one player"
endraid rtI
echo "--- 13: everyone sees the same boss"
raid cobblemonraiddens:cubone rtN:snorlax\ level=100 rtO:snorlax\ level=100
c "raidturns r rtN"; wait_state "CHOOSING" 12
for p in rtN; do c "raidturns eval $p const p = battle.sides[0].active[0]; const m = battle.dex.moves.get('spore'); p.moveSlots[0] = {move: m.name, id: m.id, pp: 20, maxpp: 20, target: m.target, disabled: false, used: false}" 1; done
c "mrtest act rtN default"; c "mrtest act rtO default"; sleep 2; wait_state "CHOOSING" 40
check "asleep for both" "$(state)" "rtN .*shown=slp.*rtO .*shown=slp"
c "raidturns eval rtN const p = battle.sides[0].active[0]; const m = battle.dex.moves.get('tackle'); p.moveSlots[0] = {move: m.name, id: m.id, pp: 20, maxpp: 20, target: m.target, disabled: false, used: false}" 1
i=0; while [ $i -lt 8 ] && state | grep -q "shown=slp"; do
  c "mrtest act rtN default"; c "mrtest act rtO default"; sleep 2; wait_state "CHOOSING" 40; i=$((i + 1))
done
check "awake for both when it wakes up" "$(state)" "rtN .*shown=none.*rtO .*shown=none"
endraid rtN rtO

# Faster than the boss, so end-of-turn effects run in the boss's turn: only lines about each player get relayed there
raid cobblemonraiddens:cubone rtP:jolteon\ level=35 rtQ:jolteon\ level=35
c "raidturns nodamage false"
c "raidturns r rtP"; wait_state "CHOOSING" 12
c "raidturns eval rtP const p = battle.sides[0].active[0]; p.setStatus('brn'); p.setItem('leftovers'); p.addVolatile('confusion')" 1
c "raidturns eval rtQ battle.sides[0].active[0].setStatus('par')" 1
i=0; while [ $i -lt 4 ]; do c "mrtest act rtP default"; c "mrtest act rtQ default"; sleep 2; wait_state "CHOOSING|no rounds" 40; i=$((i + 1)); done
s=$(state); echo "$s" | grep -Eo 'bossLog=\[[^]]*\]|health=[^ ]+'
check "the log tells the damage to the boss" "$s" "boss_damaged:[0-9]+"
check "the log tells the damage to each player" "$s" "player_damaged:[0-9]+"
check "the panel shows a teammate's status" "$s" "(rtP|RTP) .*view=brn/"
check "a teammate's burn reaches the others" "$s" "status.burn.hurt"
check "a teammate's confusion reaches the others" "$s" "confusion"
check "a teammate's Leftovers reach the others" "$s" "heal.leftovers"
check "the log tells what they healed" "$s" "player_healed:[0-9]+"
echo "$s" | grep -q "cant" && echo "PASS a teammate's paralysis reaches the others" || echo "INFO paralysis did not stop rtQ in these rounds (25% per round)"
endraid rtP rtQ
c "raidturns nodamage false"
n=$(grep -c 'raidInstance\|Exception while ticking a battle\|Mixin apply\|at knot/raidturns' "$T/console.log"); echo "errors in log: $n"
