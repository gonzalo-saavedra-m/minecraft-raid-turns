{
    name: 'raidturnshold',
    noCopy: true,
    // Last in the queue, so the one acting sees a target about to move: Protect and Sucker Punch need that
    onModifyPriority() {
        return -100;
    },
    onBeforeMovePriority: 1000,
    onBeforeMove(pokemon) {
        // Returning false fires MoveAborted, which would cancel a two-turn move: hide those volatiles until it passes
        this.effectState.kept = {};
        for (const id in pokemon.volatiles) {
            if (this.dex.conditions.getByID(id).onMoveAborted && id !== 'raidturnshold') {
                this.effectState.kept[id] = pokemon.volatiles[id];
                delete pokemon.volatiles[id];
            }
        }
        return false;
    },
    onMoveAborted(pokemon) {
        Object.assign(pokemon.volatiles, this.effectState.kept);
    },
}
