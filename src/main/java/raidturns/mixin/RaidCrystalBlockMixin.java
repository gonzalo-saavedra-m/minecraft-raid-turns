package raidturns.mixin;

import com.necro.raid.dens.common.blocks.block.RaidCrystalBlock;
import com.necro.raid.dens.common.blocks.entity.RaidCrystalBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidturns.RaidRound;

@Mixin(value = RaidCrystalBlock.class, remap = false)
public abstract class RaidCrystalBlockMixin {
	@Inject(method = "startOrJoinRaid", at = @At("HEAD"), cancellable = true)
	private void raidturns$closed(Player player, BlockState state, RaidCrystalBlockEntity crystal, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		String block = RaidRound.entryBlock(crystal.getUuid(), player.getUUID());
		if (block == null) return;
		player.displayClientMessage(Component.translatable(block), true);
		cir.setReturnValue(false);
	}
}
