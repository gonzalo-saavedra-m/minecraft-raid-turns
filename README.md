# Raid Turns

Turn-based co-op for [Cobblemon Raid Dens](https://github.com/necro50n3/cobblemon-raiddens). In Raid Dens each player
fights their own copy of the boss at their own pace. With this mod the raid plays like the main games:

- **One R starts everyone.** A 5-second countdown closes the raid, then everyone in the den enters with their selected
  Pokémon. Nobody can join after that.
- **Everyone chooses, then moves run one by one.** Order follows move priority and speed, with the boss in the list. Each
  player has 60 seconds to choose; after that, their first move with PP is used, or Struggle.
- **The boss uses one move per round against the whole party**, on its place in the order.
- **Boss status is shared.** If someone puts the boss to sleep, it sleeps for everyone with a single counter.
- **Everyone sees the same fight.** The boss's health and status are the same for the whole party, and the battle log tells
  what teammates did and how much the boss hurt each of them.
- **One Pokémon per player, no switching.** A fainted player has no more turns, keeps watching the fight and its log, and
  still gets the rewards if the party wins.
- **Fleeing takes you out.** Alone, you can start the raid again. With a party, you stay out until the raid ends.
- **Disconnecting takes you out at once**, like fleeing. If everyone disconnects, the raid is lost.
- **Party panel.** It replaces Cobblemon's own tile on the left of the screen: the timer, the boss, and each player's Pokémon,
  name, health, status, stat changes and whether they already chose. Rows follow the order of the last round.

## Install

Requires Minecraft 1.21.1, Fabric, Cobblemon 1.8.1 and **exactly** Cobblemon Raid Dens 0.13.2. Install it on the server
and on every client. A client without the mod can play, but enters with the first healthy Pokémon of its party and sees
a line above the hotbar instead of the panel.

To remove it, delete the jar and restart. It stores nothing in the world.

## Build

Put these in `libs/` (gitignored): `Cobblemon-fabric-1.8.1+1.21.1.jar`, `cobblemonraiddens-fabric-0.13.2+1.21.1.jar` and
`geckolib-fabric-1.21.1-4.9.3.jar`. Then `./gradlew build`.

## License

MIT.
