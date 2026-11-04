# Instructions for agents

Fabric mod over Cobblemon Raid Dens. Read [README.md](README.md) first for what it does.

## How it works

Raid Dens keeps one `PokemonBattle` per player against a copy of the boss, sharing health through `RaidInstance`.
`RaidRound` (one per raid, driven by the server tick) makes those battles advance together:

`COUNTDOWN → WAITING_TURN → CHOOSING → READING_SPEED → STEP … → WAITING_TURN`

Each round every copy plays **two Showdown turns**: one where its player acts and one where the boss acts. The other side
of each turn is held by the `raidturnshold` condition, whose `onBeforeMove` returns false before PP is deducted and
before sleep counts down. End-of-turn effects are switched off with `>eval` except on the copy's last turn of the round.

| File | What it is |
|---|---|
| `RaidRound.java` | All the round logic |
| `RaidTurns.java` | Server setup; `|rtspeed|` carries speeds from Showdown (boosts, paralysis, Trick Room) and `|rtview|` what the panel shows |
| `RaidTurnsClient.java` | Sends the selected Pokémon and draws the party panel: health, status and stat changes of the boss and each player |
| `mixin/BattleOverlayMixin`, `mixin/PartyOverlayMixin` | Client only: hide what Cobblemon draws on the left while the panel is shown |
| `DebugCommands.java` | `/raidturns …` for fake players; only exists if a file named `raidturns-debug` is in the game folder |
| `mixin/PokemonBattleMixin` | Holds `checkForInputDispatch` until the round allows it |
| `mixin/PlayerBattleActorMixin` | Hides every move request; `RaidRound.sendMenu` sends them when it is time to choose |
| `mixin/RaidInstanceMixin` | Always sync; no state copies in the boss's turn; one heal; one life; shows a hit when it lands |
| `tools/test-raid.sh` | End-to-end test with Carpet's fake players; the definition of "works" |

## Invariants (don't break them)

1. **Cobblemon dispatches only when no actor has `mustChoose` and the dispatch queue is empty.** Set both responses and
   `mustChoose = false` before calling `checkForInputDispatch`, and wait for `ready()` before the next step.
2. **The move menu only opens through `sendMenu`, when CHOOSING starts.** Cobblemon drops a choice while the server's
   `mustChoose` is false, and its own request reaches the client earlier: an early choice would be lost.
3. **Mixins target private names of Raid Dens 0.13.2** (`lambda$updateBattleState$25`, for one). `fabric.mod.json` pins
   the exact version; re-check them all on any upgrade.
4. **What clients show of the boss comes from `RaidRound.publishBoss`, never from a copy.** Each copy's Showdown has its
   own health, and Raid Dens copies status with a silent `>eval`, so no copy's packets can be trusted.
   `PlayerBattleActorMixin` hides the copies' health, and `publishBoss` runs after every step. The battle log is shared
   the same way: `relayLog` forwards a copy's lines, and each health change gets its own line.
5. Player-facing text follows the translation files. Keep it short and concrete, without emojis.

## Test

On a server with this mod, Carpet, `mipack-testkit` and the `raidturns-debug` file: `tools/test-raid.sh <server folder>`.
Fake players can't show the client side: the move menu, animations, the selected Pokémon packet and the panel need a real
client.
