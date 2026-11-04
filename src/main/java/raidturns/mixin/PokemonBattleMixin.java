package raidturns.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidturns.RaidRound;

@Mixin(value = PokemonBattle.class, remap = false)
public abstract class PokemonBattleMixin {
	@Unique
	private static boolean raidturns$converting;

	/** The round decides when each copy of the raid sends its choices to Showdown. */
	@Inject(method = "checkForInputDispatch", at = @At("HEAD"), cancellable = true)
	private void raidturns$hold(CallbackInfo ci) {
		PokemonBattle battle = (PokemonBattle) (Object) this;
		RaidRound round = RaidRound.of(battle);
		if (round != null && !round.canDispatch(battle)) ci.cancel();
	}

	/** Spectators bypass the actor's filter: they also only get the raid's health. */
	@Inject(method = "sendSpectatorUpdate", at = @At("HEAD"), cancellable = true)
	private void raidturns$hideCopyHealth(com.cobblemon.mod.common.api.net.NetworkPacket<?> packet, CallbackInfo ci) {
		if (!(packet instanceof com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket health) || !health.getPnx().startsWith("p2")) return;
		RaidRound round = RaidRound.of((PokemonBattle) (Object) this);
		if (round != null && round.hidesBossHealth()) ci.cancel();
	}

	/** Each round is two Showdown turns: the battle log counts rounds instead. Other lines also reach the rest of the party. */
	@Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
	private void raidturns$countRounds(Component message, CallbackInfo ci) {
		if (raidturns$converting) return;
		PokemonBattle battle = (PokemonBattle) (Object) this;
		RaidRound round = RaidRound.of(battle);
		if (round == null) return;
		if (!(message.getContents() instanceof TranslatableContents contents) || !contents.getKey().endsWith(".battle.turn")) {
			round.relayLog(battle, message);
			return;
		}
		if (contents.getArgs().length == 0 || !(contents.getArgs()[0] instanceof Integer turn)) return;
		ci.cancel();
		if (turn % 2 == 0) return;
		round.onRoundShown(battle, (turn + 1) / 2);
		raidturns$converting = true;
		try {
			battle.broadcastChatMessage(Component.translatable(contents.getKey(), (turn + 1) / 2).withStyle(message.getStyle()));
		} finally {
			raidturns$converting = false;
		}
	}
}
