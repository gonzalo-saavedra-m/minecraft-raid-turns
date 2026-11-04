package raidturns.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.necro.raid.dens.common.raids.RaidInstance;
import com.necro.raid.dens.common.showdown.events.ShowdownEvent;
import com.necro.raid.dens.common.showdown.events.ShowdownEvents;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raidturns.RaidRound;

@Mixin(value = RaidInstance.class, remap = false)
public abstract class RaidInstanceMixin {
	private RaidRound raidturns$round() {
		return RaidRound.of((RaidInstance) (Object) this);
	}

	/** Without this, Raid Dens stops copying states above max_players_for_support. */
	@Inject(method = "canSync", at = @At("HEAD"), cancellable = true)
	private void raidturns$alwaysSync(CallbackInfoReturnable<Boolean> cir) {
		if (raidturns$round() != null) cir.setReturnValue(true);
	}

	/**
	 * In the boss's turn every copy already runs the move: copying a boost would apply it once per player. A status is
	 * still copied, since setting it twice changes nothing and it may come from one copy only (Static on contact).
	 */
	@Inject(method = "lambda$updateBattleState$25", at = @At("HEAD"), cancellable = true)
	private void raidturns$noStateCopy(PokemonBattle battle, ShowdownEvent event, CallbackInfo ci) {
		RaidRound round = raidturns$round();
		if (round != null && round.isBossPhase() && !(event instanceof ShowdownEvents.SetStatusShowdownEvent) && !(event instanceof ShowdownEvents.CureStatusShowdownEvent)) ci.cancel();
	}

	@Inject(method = "broadcastToOthers", at = @At("HEAD"), cancellable = true)
	private void raidturns$noBroadcast(ShowdownEvent event, PokemonBattle battle, CallbackInfo ci) {
		RaidRound round = raidturns$round();
		if (round != null && round.isBossPhase()) ci.cancel();
	}

	/** A boss healing in its turn counts once, not once per copy. */
	@Inject(method = "syncHealth", at = @At("HEAD"), cancellable = true)
	private void raidturns$healOnce(ServerPlayer player, PokemonBattle battle, float amount, CallbackInfo ci) {
		RaidRound round = raidturns$round();
		if (round != null && (RaidRound.debugNoDamage && amount > 0 || amount < 0 && round.ignoresBossHeal(battle))) ci.cancel();
	}

	/** Only the raid's own health reaches the boss bar: each copy's Showdown still reports the copy at full health. */
	@Inject(method = "sendHealthPacket", at = @At("HEAD"))
	private void raidturns$healthStart(PokemonBattle battle, CallbackInfo ci) {
		RaidRound round = raidturns$round();
		if (round != null) round.showBossHealth(true);
	}

	@Inject(method = "sendHealthPacket", at = @At("RETURN"))
	private void raidturns$healthEnd(PokemonBattle battle, CallbackInfo ci) {
		RaidRound round = raidturns$round();
		if (round != null) round.showBossHealth(false);
	}

	/** One Pokémon per player and no entries once started: lives above 1 would leave a player out but not failed. */
	@Inject(method = "loseLife", at = @At("HEAD"), cancellable = true)
	private void raidturns$oneLife(java.util.UUID player, CallbackInfoReturnable<Boolean> cir) {
		if (raidturns$round() != null) cir.setReturnValue(true);
	}
}
