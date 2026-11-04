package raidturns.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.necro.raid.dens.common.raids.RaidInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = RaidInstance.class, remap = false)
public interface RaidInstanceAccessor {
	@Invoker("sendHealthPacket")
	void raidturns$sendHealth(PokemonBattle battle);
}
