package raidturns.mixin;

import com.cobblemon.mod.common.client.gui.PartyOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidTurnsClient;

@Mixin(value = PartyOverlay.class, remap = false)
public abstract class PartyOverlayMixin {
	/** A player between battles, like one who just fainted, would get the party list on top of the panel. */
	@Inject(method = "method_1753", at = @At("HEAD"), cancellable = true, require = 0)
	private void raidturns$hideParty(CallbackInfo ci) {
		if (RaidTurnsClient.panelShown()) ci.cancel();
	}
}
