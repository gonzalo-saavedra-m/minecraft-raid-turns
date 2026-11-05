#!/bin/sh
# End-to-end test with Carpet's fake players on a running server with this mod, Carpet, mipack-testkit and a file named
# raidturns-debug in the game folder. Usage: tools/test-raid.sh <server folder> [group...]. Prints PASS/FAIL per case.
# Groups: twoturn protect priority robust rejected disconnect log, or moves for all of them.
# Group extra repeats those cases with other moves that take the same path: run it before publishing. Without groups it runs everything.
T=$1
alive() { kill -0 "$(cat "$T/server.pid")" 2>/dev/null || { echo "FAIL the server crashed: $(grep -m1 -A1 'Encountered an unexpected' "$T/console.log" | tail -1 | cut -c1-200)"; exit 1; }; }
c() { alive; echo "$1" > "$T/in.fifo"; sleep "${2:-0.5}"; }
# ask "<command>" <reply prefix>: runs it and prints its reply
ask() {
  alive; n=$(wc -l < "$T/console.log"); echo "$1" > "$T/in.fifo"; i=0
  while [ $i -lt 40 ]; do
    out=$(tail -n +$((n + 1)) "$T/console.log" | grep -iF "$2" | tail -1)
    [ -n "$out" ] && break; sleep 0.25; i=$((i + 1))
  done
  echo "${out#*]: }"
}
check() { if echo "$2" | grep -Eiq -- "$3"; then echo "PASS $1"; else echo "FAIL $1 -> $(echo "$2" | cut -c1-900)"; fi; }
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
endraid() { for p in "$@"; do c "raidturns quit $p" 0.5; done; sleep 3; }
expected_blocked() { ask "raidturns enter $1 $X 200 4" "enter $1"; }

# duel <name> <player speed> <boss speed> <boss's only move> <player's moves>: speeds are set so the order is exact.
# The boss's first request is stale and picks a slot by number, so all four are that move. A first round with Splash
# refreshes the request. Use an attack: the stale request also carries a target, which a self move rejects (a 30 s stall).
duel() {
  P=$1
  raid cobblemonraiddens:cubone "$P:snorlax level=100 moves=$5"
  c "raidturns r $P"; wait_state "CHOOSING" 12
  c "raidturns eval $P const p = battle.sides[0].active[0], b = battle.sides[1].active[0], m = battle.dex.moves.get('$4'); let s; p.storedStats.spe = $2; b.storedStats.spe = $3; s = {move: m.name, id: m.id, pp: 40, maxpp: 40, target: m.target, disabled: false, used: false}; b.moveSlots = [s, s, s, s]" 1
  c "mrtest act $P move splash"; wait_state "CHOOSING.*turn=3" 30
}
# round <player> <move> <turn it ends on>: chooses like the real client (a target for attacks, none for self moves and locked moves)
round() {
  case $2 in locked:*|self:*) c "mrtest act $1 move ${2#*:}" ;; *) c "raidturns act $1 $2" ;; esac
  wait_state "CHOOSING.*turn=$3|no rounds" 30
}
# since <player> <turn>: their log from that Showdown turn on
since() { log "$1" | sed "s/.*|turn|$2//"; }
absent() { if echo "$2" | grep -Eiq -- "$3"; then echo "FAIL $1 -> $(echo "$2" | cut -c1-900)"; else echo "PASS $1"; fi; }
crashed() { grep -c 'Encountered an unexpected exception' "$T/console.log"; }

twoturn_case() {
    move=$1
    echo "--- two-turn move ($move), boss acts first in both rounds: this crashed the server"
    duel rtW 1 500 tackle "$move,splash"
    round rtW "$move" 5
    check "$move charges in the player's slot" "$(since rtW 3)" "-prepare\|p1a"
    round rtW "locked:$move" 7
    s=$(since rtW 5)
    check "$move: while still away the boss misses" "$s" "-miss\|p2a"
    check "$move hits on the second round" "$s" "\|move\|p1a: #\|(Fly|Dig)\|p2a"
    absent "$move: the player was not hit while away" "$s" "-damage\|p1a"
    check "$move: the round after is a normal one" "$(state)" "CHOOSING.*turn=7"
    endraid rtW

    echo "--- two-turn move ($move), player acts first"
    duel rtW 500 1 tackle "$move,splash"
    round rtW "$move" 5
    s=$(since rtW 3)
    check "$move: the boss misses in the round it charges" "$s" "-miss\|p2a"
    absent "$move: no damage in the charging round" "$s" "-damage\|p1a"
    round rtW "locked:$move" 7
    s=$(since rtW 5)
    check "$move hits on the second round" "$s" "\|move\|p1a: #\|(Fly|Dig)\|p2a"
    check "$move: the boss hits again once it landed" "$s" "-damage\|p1a"
    endraid rtW
}

group_twoturn() {
  twoturn_case fly

  echo "--- a rampage (Outrage) is not lost when held"
  duel rtW 1 500 tackle "outrage,splash"
  round rtW self:outrage 5
  round rtW locked:outrage 7
  check "Outrage keeps going in the next round" "$(since rtW 5)" "\|move\|p1a: #\|Outrage"
  endraid rtW

}

protect_case() {
    move=$1
    echo "--- $move blocks the boss's move, whatever the speeds"
    for speeds in "500 1" "1 500"; do
      duel rtW $speeds tackle "$move,splash"
      round rtW "self:$move" 5
      s=$(since rtW 3)
      check "$move works ($speeds)" "$s" "-singleturn\|p1a"
      check "$move blocks the hit ($speeds)" "$s" "-activate\|p1a: #\|move: Protect"
      absent "$move: no damage ($speeds)" "$s" "-damage\|p1a"
      round rtW self:splash 7
      check "$move lasts one round ($speeds)" "$(since rtW 5)" "-damage\|p1a"
      endraid rtW
    done
}

group_protect() {
  protect_case protect

  echo "--- the boss's Protect blocks everyone who acts after it"
  raid cobblemonraiddens:cubone "rtW:snorlax level=100 moves=tackle,splash" "rtX:snorlax level=100 moves=tackle,splash"
  c "raidturns r rtW"; wait_state "CHOOSING" 12
  for p in rtW rtX; do c "raidturns eval $p const b = battle.sides[1].active[0], m = battle.dex.moves.get('protect'); const s = {move: m.name, id: m.id, pp: 40, maxpp: 40, target: m.target, disabled: false, used: false}; b.moveSlots = [s, s, s, s]" 1; done
  c "mrtest act rtW move splash"; c "mrtest act rtX move splash"; wait_state "CHOOSING.*turn=3.*turn=3" 30
  # The first Protect never fails: without this it may be a second one in a row, which each copy rolls by itself
  for p in rtW rtX; do c "raidturns eval $p delete battle.sides[1].active[0].volatiles.stall" 0.5; done
  c "raidturns act rtW tackle"; c "raidturns act rtX tackle"; wait_state "CHOOSING.*turn=5.*turn=5" 30
  for p in rtW rtX; do check "boss's Protect blocks $p" "$(since $p 3)" "-activate\|p2a: #\|move: Protect"; done
  endraid rtW rtX

}

priority_case() {
    move=$1
    echo "--- priority: $move goes before a faster boss"
    duel rtW 1 500 tackle "$move,splash"
    round rtW "$move" 5
    s=$(state)
    check "$move first in the order" "$s" "order=\[RTW:[1-9]:1, boss:0:"
    check "$move: the panel follows the turn order" "$s" "panel=\[rtw, boss\]"
    s=$(since rtW 3 | grep -Eo '\|move\|p[12]a' | tr '\n' ' ')
    check "$move: the player moves first in the log" "$s" "^\|move\|p1a \|move\|p2a"
    absent "$move does not fail" "$(since rtW 3)" "-fail\|p1a|-notarget"
    endraid rtW
}

group_priority() {
  priority_case quickattack

  echo "--- priority: Sucker Punch needs the boss about to attack"
  duel rtW 1 500 tackle "suckerpunch,splash"
  round rtW suckerpunch 5
  check "Sucker Punch hits a faster boss" "$(since rtW 3)" "\|move\|p1a: #\|Sucker Punch\|p2a"
  absent "Sucker Punch does not fail" "$(since rtW 3)" "\|move\|p1a: #\|Sucker Punch\|\[still\]|-fail\|p1a"
  endraid rtW

  echo "--- priority: Fake Out in the first round flinches the boss"
  raid cobblemonraiddens:cubone "rtW:snorlax level=100 moves=fakeout,splash"
  c "raidturns r rtW"; wait_state "CHOOSING" 12
  c "raidturns eval rtW battle.sides[0].active[0].storedStats.spe = 1; battle.sides[1].active[0].storedStats.spe = 500" 1
  round rtW fakeout 3
  s=$(since rtW 1)
  absent "Fake Out does not fail" "$s" "\|move\|p1a: #\|Fake Out\|\[still\]|-fail\|p1a"
  check "the boss flinches" "$s" "\|cant\|p2a: #\|flinch"
  endraid rtW

  echo "--- priority: the boss's priority move beats a faster player"
  duel rtW 500 1 quickattack "tackle,splash"
  round rtW tackle 5
  s=$(state)
  check "the boss first in the order" "$s" "order=\[boss:1:1, RTW:0:"
  check "the panel puts the boss first" "$s" "panel=\[boss, rtw\]"
  s=$(since rtW 3 | grep -Eo '\|move\|p[12]a' | tr '\n' ' ')
  check "the boss moves first in the log" "$s" "^\|move\|p2a \|move\|p1a"
  endraid rtW

}

group_robust() {
  echo "--- a move the boss disabled falls back to the default move instead of crashing"
  duel rtW 1 500 tackle "tackle,splash"
  c "raidturns eval rtW const b = battle.sides[1].active[0], m = battle.dex.moves.get('disable'); const s = {move: m.name, id: m.id, pp: 40, maxpp: 40, target: m.target, disabled: false, used: false}; b.moveSlots = [s, s, s, s]" 1
  round rtW tackle 5
  round rtW tackle 7
  round rtW tackle 9
  check "still running after being disabled" "$(state)" "CHOOSING.*turn=9"
  endraid rtW

}

group_disconnect() {
  echo "--- disconnecting takes you out at once"
  raid cobblemonraiddens:cubone "rtW:snorlax level=100 moves=tackle,splash" "rtX:snorlax level=100 moves=tackle,splash"
  c "raidturns r rtW"; wait_state "CHOOSING" 12
  c "raidturns quit rtX" 2
  s=$(state)
  check "they leave the raid" "$s" "players=1 "
  absent "they leave the panel" "$(echo "$s" | grep -Eo 'panel=[^]]*')" "rtx"
  c "player rtX spawn at $((X + 8)) 200 0" 2
  check "they are not a participant when they come back" "$(ask 'raidturns inraid rtX' 'inraid rtX')" "participant=false"
  check "they cannot rejoin while it lasts" "$(expected_blocked rtX)" "blocked raidturns.banned"
  c "raidturns act rtW tackle"; wait_state "CHOOSING.*turn=3" 30
  check "the others keep going" "$(state)" "CHOOSING.*turn=3"
  endraid rtX

  echo "--- if the last one disconnects the raid is lost"
  c "raidturns quit rtW" 3
  check "raid lost after everyone left, one by one" "$(state)" "no rounds last=FAILED"
  raid cobblemonraiddens:cubone "rtW:snorlax level=100 moves=tackle,splash" "rtX:snorlax level=100 moves=tackle,splash"
  c "raidturns r rtW"; wait_state "CHOOSING" 12
  c "raidturns quit rtW" 0.2; c "raidturns quit rtX" 3
  check "raid lost after everyone left at once" "$(state)" "no rounds last=FAILED"
  raid cobblemonraiddens:cubone "rtW:snorlax level=100 moves=tackle,splash"
  c "raidturns r rtW"; wait_state "CHOOSING" 12
  c "raidturns quit rtW" 3
  check "raid lost when the only player leaves" "$(state)" "no rounds last=FAILED"

  echo "--- disconnecting before the turns start"
  raid cobblemonraiddens:cubone "rtW:snorlax level=100 moves=tackle,splash" "rtX:snorlax level=100 moves=tackle,splash"
  c "raidturns quit rtX" 2; c "player rtX spawn at $((X + 8)) 200 0" 2
  check "before R: out of the raid" "$(ask 'raidturns inraid rtX' 'inraid rtX')" "participant=false"
  check "before R: the other one stays" "$(ask 'raidturns inraid rtW' 'inraid rtW')" "participant=true"
  c "raidturns r rtW"; wait_state "CHOOSING" 12
  check "before R: the raid starts without them" "$(state)" "players=1 "
  endraid rtW rtX
  raid cobblemonraiddens:cubone "rtW:snorlax level=100 moves=tackle,splash" "rtX:snorlax level=100 moves=tackle,splash"
  c "raidturns r rtW"; c "raidturns quit rtX" 1
  wait_state "CHOOSING" 12
  check "in the countdown: the raid starts without them" "$(state)" "players=1 "
  endraid rtW

}

group_rejected() {
  echo "--- a choice Showdown rejects stops the turns, not the server"
  duel rtW 1 500 tackle "outrage,splash"
  c "raidturns act rtW outrage" 3
  wait_state "no rounds" 70
  check "the round was stopped" "$(state)" "no rounds"
  endraid rtW

}

group_log() {
  echo "--- each client gets each log line once"
  c "raidturns nodamage false"
  raid cobblemonraiddens:cubone "rtW:snorlax level=12 moves=tackle,splash" "rtX:snorlax level=12 moves=tackle,splash" "rtY:snorlax level=12 moves=tackle,splash"
  c "raidturns r rtW"; wait_state "CHOOSING" 12
  # Poison on the boss hurts it in every copy at the end of the round
  for p in rtW rtX rtY; do c "raidturns eval $p battle.sides[1].active[0].setStatus('psn')" 0.5; done
  for n in 3 5; do
    for p in rtW rtX rtY; do c "raidturns act $p tackle"; done
    wait_state "CHOOSING.*turn=$n.*turn=$n.*turn=$n" 40
  done
  check "the party panel lists everyone in turn order" "$(state)" "panel=\[[^]]*boss[^]]*\]"
  for p in rtW rtX rtY; do
    ask "raidturns chat $p" "[raidturns] chat $p" | sed 's/.*chat [^ ]* ; //' | sed 's/ ; /\n/g' > "$T/chat-$p.txt"
    echo "chat of $p:"; cut -c1-160 "$T/chat-$p.txt"
    # Within a round no line repeats. Joining repeats on purpose, and equal damage lines are different hits
    d=$(awk '/battle\.turn/ { delete seen } /raid_energy|boss_damaged/ { next } seen[$0]++ == 1' "$T/chat-$p.txt")
    [ -z "$d" ] && echo "PASS $p: no line repeats within a round" || echo "FAIL $p repeated: $d"
    # The boss's poison hurts it once per round, not once per copy
    n=$(awk '/battle\.turn/ { if (x != 1 && started) bad++; x = 0; started = 1 } /poison.hurt|hurt by poison/ { x++ } END { print started ? bad + 0 : "no rounds" }' "$T/chat-$p.txt")
    [ "$n" = 0 ] && echo "PASS $p: the boss's poison shows once per round" || echo "FAIL $p: poison lines wrong in $n rounds"
  done
  endraid rtW rtX rtY
}

group_extra() {
  twoturn_case dig
  protect_case detect
  for move in extremespeed aquajet machpunch; do priority_case "$move"; done
}

run_group() {
  c "raidturns nodamage true"
  case $1 in
    moves) for g in twoturn protect priority robust rejected disconnect log; do run_group $g; done ;;
    *) group_$1 ;;
  esac
}
if [ -n "$2" ]; then
  shift
  for group; do run_group "$group"; done
  check "the server never crashed" "crashes=$(crashed)" "crashes=0"
  exit
fi

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
run_group moves
run_group extra
n=$(grep -c 'raidInstance\|Exception while ticking a battle\|Mixin apply\|at knot/raidturns' "$T/console.log"); echo "errors in log: $n"
