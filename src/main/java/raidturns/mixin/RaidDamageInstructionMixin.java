package raidturns.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.necro.raid.dens.common.showdown.instructions.RaidDamageInstruction;
import com.necro.raid.dens.common.util.IRaidBattle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RaidDamageInstruction.class, remap = false)
public abstract class RaidDamageInstructionMixin {
	/** Damage of a battle that already left its raid: Raid Dens would throw a NullPointerException and end the battle. */
	@Inject(method = "postActionEffect", at = @At("HEAD"), cancellable = true)
	private void raidturns$noRaid(PokemonBattle battle, CallbackInfo ci) {
		if (((IRaidBattle) battle).crd_getRaidBattle() == null) ci.cancel();
	}
}
