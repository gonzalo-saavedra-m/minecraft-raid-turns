package raidturns.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidTurnsClient;

@Pseudo
@Mixin(targets = "name.modid.client.BattleStatTabRenderer", remap = false)
public abstract class BattleExtrasStatsMixin {
	/** Cobblemon Battle Extras anchors its stat strip to the tile the panel replaces, covering the panel's rows. The panel already shows those stats. */
	@Inject(method = "renderCompactPanels", at = @At("HEAD"), cancellable = true, require = 0)
	private static void raidturns$hideStrip(CallbackInfo ci) {
		if (RaidTurnsClient.panelShown()) ci.cancel();
	}
}
