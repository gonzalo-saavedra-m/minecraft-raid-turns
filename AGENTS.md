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
| `RaidTurns.java` | Server setup; `|rtspeed|` carries speeds from Showdown (boosts, paralysis, Trick Room) |
| `RaidTurnsClient.java` | Sends the selected Pokémon and draws the party panel |
| `DebugCommands.java` | `/raidturns …` for fake players; only exists if a file named `raidturns-debug` is in the game folder |
| `mixin/PokemonBattleMixin` | Holds `checkForInputDispatch` until the round allows it |
| `mixin/PlayerBattleActorMixin` | Hides every move request; `RaidRound.sendMenu` sends them when it is time to choose |
| `mixin/RaidInstanceMixin` | Always sync; no state copies in the boss's turn; one heal; one life |
| `tools/test-raid.sh` | End-to-end test with Carpet's fake players; the definition of "works" |

## Invariants (don't break them)

1. **Cobblemon dispatches only when no actor has `mustChoose` and the dispatch queue is empty.** Set both responses and
   `mustChoose = false` before calling `checkForInputDispatch`, and wait for `ready()` before the next step.
2. **The move menu only opens through `sendMenu`, when CHOOSING starts.** Cobblemon drops a choice while the server's
   `mustChoose` is false, and its own request reaches the client earlier: an early choice would be lost.
3. **Mixins target private names of Raid Dens 0.13.2** (`lambda$updateBattleState$25`, for one). `fabric.mod.json` pins
   the exact version; re-check them all on any upgrade.
4. Player-facing text follows the translation files. Keep it short and concrete, without emojis.

## Test

On a server with this mod, Carpet, `mipack-testkit` and the `raidturns-debug` file: `tools/test-raid.sh <server folder>`.
Fake players can't show the client side: the move menu, animations, the selected Pokémon packet and the panel need a real
client.
