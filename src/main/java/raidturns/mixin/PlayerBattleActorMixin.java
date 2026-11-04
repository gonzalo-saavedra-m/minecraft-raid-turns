package raidturns.mixin;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidRound;

@Mixin(value = PlayerBattleActor.class, remap = false)
public abstract class PlayerBattleActorMixin {
	/** The turns the server answers by itself must not open the move menu. */
	@Inject(method = "sendUpdate", at = @At("HEAD"), cancellable = true)
	private void raidturns$hideInternalRequests(NetworkPacket<?> packet, CallbackInfo ci) {
		if (!(packet instanceof BattleMakeChoicePacket || packet instanceof BattleQueueRequestPacket)) return;
		var battle = ((PlayerBattleActor) (Object) this).getBattle();
		RaidRound round = RaidRound.of(battle);
		if (round != null && round.isInternal(battle)) ci.cancel();
	}
}
