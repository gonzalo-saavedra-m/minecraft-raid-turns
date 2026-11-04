package raidturns.mixin;

import com.cobblemon.mod.common.api.pokemon.status.Status;
import com.necro.raid.dens.common.raids.battle.RaidPokemon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RaidPokemon.class, remap = false)
public interface RaidPokemonAccessor {
	@Accessor("status")
	Status raidturns$status();
}
