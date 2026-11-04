package raidturns.mixin;

import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.necro.raid.dens.common.network.packets.RaidChallengePacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidRound;

@Mixin(value = RaidChallengePacket.class, remap = false)
public abstract class RaidChallengePacketMixin {
	/** The first R of a raid starts the countdown for the whole party; later ones are rejected. */
	@Inject(method = "handleServer", at = @At("HEAD"), cancellable = true)
	private void raidturns$startTogether(ServerPlayer player, CallbackInfo ci) {
		if (RaidRound.onChallenge((RaidChallengePacket) (Object) this, player)) ci.cancel();
	}

	/** Whoever did not press R may be across the arena when the raid starts. */
	@Redirect(method = "handleServer", at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/util/PlayerExtensionsKt;canInteractWith"))
	private boolean raidturns$anyDistance(ServerPlayer player, net.minecraft.world.entity.LivingEntity entity, float distance) {
		return RaidRound.starting || PlayerExtensionsKt.canInteractWith(player, entity, distance);
	}
}
