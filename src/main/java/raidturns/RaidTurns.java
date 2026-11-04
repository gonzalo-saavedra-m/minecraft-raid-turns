package raidturns;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.battles.ShowdownInterpreter;
import com.cobblemon.mod.common.battles.dispatch.InterpreterInstruction;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RaidTurns implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("raidturns");

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playC2S().register(SelectedPokemonPayload.TYPE, SelectedPokemonPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(GroupStatePayload.TYPE, GroupStatePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestMenuPayload.TYPE, RequestMenuPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RequestMenuPayload.TYPE, (payload, context) -> RaidRound.onMenuRequest(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(SelectedPokemonPayload.TYPE,
				(payload, context) -> RaidRound.SELECTED.put(context.player().getUUID(), payload.pokemon()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> RaidRound.SELECTED.remove(handler.getPlayer().getUUID()));
		ServerTickEvents.END_SERVER_TICK.register(RaidRound::tickAll);
		// |rtspeed|<player>|<boss>: the speeds Showdown would sort by, with boosts, paralysis and Trick Room
		ShowdownInterpreter.registerUpdateInstructionParser("rtspeed", (battle, instructionSet, message, messages) -> {
			RaidRound.onSpeeds(battle, parse(message, 0), parse(message, 1));
			return (InterpreterInstruction) ignored -> {};
		});
		// |rtview|<player's status>|<player's boosts>|<boss's boosts>: what the panel shows, since no copy tells the client about boosts
		ShowdownInterpreter.registerUpdateInstructionParser("rtview", (battle, instructionSet, message, messages) -> {
			RaidRound.onView(battle, message.argumentAt(0), message.argumentAt(1), message.argumentAt(2));
			return (InterpreterInstruction) ignored -> {};
		});
		DebugCommands.register();
	}

	private static int parse(BattleMessage message, int index) {
		String value = message.argumentAt(index);
		return value == null ? 0 : Integer.parseInt(value);
	}
}
