package raidturns.mixin;

import com.cobblemon.mod.common.api.net.NetworkPacket;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidRound;

@Mixin(value = PlayerBattleActor.class, remap = false)
public abstract class PlayerBattleActorMixin {
	/** The move menu only opens when the round asks everyone to choose, and the boss health bar only shows the raid's health. */
	@Inject(method = "sendUpdate", at = @At("HEAD"), cancellable = true)
	private void raidturns$hideInternalRequests(NetworkPacket<?> packet, CallbackInfo ci) {
		if (packet instanceof BattleHealthChangePacket health && health.getPnx().startsWith("p1")) {
			RaidRound round = RaidRound.of(((PlayerBattleActor) (Object) this).getBattle());
			if (round != null) round.onPlayerHealth(((PlayerBattleActor) (Object) this).getBattle(), health.getNewHealth());
			return;
		}
		boolean menu = packet instanceof BattleMakeChoicePacket || packet instanceof BattleQueueRequestPacket;
		boolean bossHealth = packet instanceof BattleHealthChangePacket health && health.getPnx().startsWith("p2");
		if (!menu && !bossHealth) return;
		var battle = ((PlayerBattleActor) (Object) this).getBattle();
		RaidRound round = RaidRound.of(battle);
		if (round != null && (menu ? round.hidesMenu() : round.hidesBossHealth())) ci.cancel();
	}
}
