package raidturns.mixin;

import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidTurnsClient;

@Mixin(value = BattleOverlay.class, remap = false)
public abstract class BattleOverlayMixin {
	/** During a raid the party panel takes the left side of the screen, and shows this same data for everyone. */
	@Inject(method = "drawTile", at = @At("HEAD"), cancellable = true, require = 0)
	private void raidturns$hideOwnTile(GuiGraphics graphics, float tickDelta, ActiveClientBattlePokemon pokemon, boolean left, int rank, PokedexEntryProgress progress, boolean hasCommand, boolean hovered, boolean compact, CallbackInfo ci) {
		if (left && RaidTurnsClient.panelShown()) ci.cancel();
	}
}
