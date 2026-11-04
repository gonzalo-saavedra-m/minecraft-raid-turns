package raidturns.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidRound;

@Mixin(value = PokemonBattle.class, remap = false)
public abstract class PokemonBattleMixin {
	/** The round decides when each copy of the raid sends its choices to Showdown. */
	@Inject(method = "checkForInputDispatch", at = @At("HEAD"), cancellable = true)
	private void raidturns$hold(CallbackInfo ci) {
		PokemonBattle battle = (PokemonBattle) (Object) this;
		RaidRound round = RaidRound.of(battle);
		if (round != null && !round.canDispatch(battle)) ci.cancel();
	}
}
