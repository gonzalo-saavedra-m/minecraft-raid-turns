package raidturns.mixin;

import com.necro.raid.dens.common.raids.helpers.RaidJoinHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidRound;

@Mixin(value = RaidJoinHelper.class, remap = false)
public abstract class RaidJoinHelperMixin {
	/** Raid Dens only removes a player who still has a battle, and Cobblemon has already stopped it by now. */
	@Inject(method = "onPlayerDisconnect", at = @At("HEAD"), cancellable = true)
	private static void raidturns$leaveStarted(Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer server && RaidRound.onDisconnect(server)) ci.cancel();
	}

	/** Before the raid starts Raid Dens only closes it for the last one out, leaving the others registered. */
	@Inject(method = "onPlayerDisconnect", at = @At("TAIL"))
	private static void raidturns$leaveNotStarted(Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer server) RaidRound.dropParticipant(server);
	}
}
