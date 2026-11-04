package raidturns.mixin;

import com.necro.raid.dens.common.blocks.entity.RaidCrystalBlockEntity;
import com.necro.raid.dens.common.network.packets.RequestResponsePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidRound;

@Mixin(value = RequestResponsePacket.class, remap = false)
public abstract class RequestResponsePacketMixin {
	/** A join request accepted after the raid started. */
	@Inject(method = "acceptRequest", at = @At("HEAD"), cancellable = true)
	private void raidturns$closed(ServerPlayer host, Player player, RaidCrystalBlockEntity crystal, CallbackInfo ci) {
		String block = RaidRound.entryBlock(crystal.getUuid(), player.getUUID());
		if (block == null) return;
		player.displayClientMessage(Component.translatable(block), true);
		ci.cancel();
	}
}
