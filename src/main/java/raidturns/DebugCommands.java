package raidturns;

import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.necro.raid.dens.common.blocks.block.RaidCrystalBlock;
import com.necro.raid.dens.common.blocks.entity.RaidCrystalBlockEntity;
import com.necro.raid.dens.common.data.raid.RaidCycleMode;
import com.necro.raid.dens.common.data.raid.RaidTier;
import com.necro.raid.dens.common.data.raid.RaidType;
import com.necro.raid.dens.common.network.packets.RaidChallengePacket;
import com.necro.raid.dens.common.raids.helpers.RaidHelper;
import com.necro.raid.dens.common.raids.helpers.RaidJoinHelper;
import com.necro.raid.dens.common.raids.helpers.RaidRegionHelper;
import com.necro.raid.dens.common.util.RaidUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

import java.nio.file.Files;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Drives a raid with Carpet's fake players, which have no client. Only exists if the file "raidturns-debug" is in the game folder. */
final class DebugCommands {
	private static final ResourceLocation CRYSTAL = ResourceLocation.fromNamespaceAndPath("cobblemonraiddens", "raid_crystal_block");

	static void register() {
		if (!Files.exists(FabricLoader.getInstance().getGameDir().resolve("raidturns-debug"))) return;
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(literal("raidturns").requires(s -> s.hasPermission(2))
				.then(literal("crystal").then(argument("pos", BlockPosArgument.blockPos()).then(argument("boss", StringArgumentType.greedyString())
						.executes(c -> reply(c.getSource(), crystal(c.getSource().getLevel(), BlockPosArgument.getBlockPos(c, "pos"), StringArgumentType.getString(c, "boss")))))))
				.then(literal("enter").then(argument("player", EntityArgument.player()).then(argument("pos", BlockPosArgument.blockPos())
						.executes(c -> reply(c.getSource(), enter(EntityArgument.getPlayer(c, "player"), BlockPosArgument.getBlockPos(c, "pos")))))))
				.then(literal("r").then(argument("player", EntityArgument.player())
						.executes(c -> reply(c.getSource(), challenge(EntityArgument.getPlayer(c, "player"))))))
				.then(literal("act").then(argument("player", EntityArgument.player()).then(argument("move", StringArgumentType.word())
						.executes(c -> reply(c.getSource(), act(EntityArgument.getPlayer(c, "player"), StringArgumentType.getString(c, "move")))))))
				.then(literal("log").then(argument("player", EntityArgument.player())
						.executes(c -> reply(c.getSource(), log(EntityArgument.getPlayer(c, "player"))))))
				.then(literal("eval").then(argument("player", EntityArgument.player()).then(argument("js", StringArgumentType.greedyString())
						.executes(c -> reply(c.getSource(), eval(EntityArgument.getPlayer(c, "player"), StringArgumentType.getString(c, "js")))))))
				.then(literal("bossmove").then(argument("move", StringArgumentType.word()).executes(c -> {
					String move = StringArgumentType.getString(c, "move");
					RaidRound.debugBossMove = move.equals("none") ? null : move;
					return reply(c.getSource(), "bossmove " + move);
				})))
				.then(literal("inraid").then(argument("player", EntityArgument.player()).executes(c -> {
					var p = EntityArgument.getPlayer(c, "player");
					return reply(c.getSource(), "inraid " + p.getScoreboardName() + " dim=" + RaidUtils.isRaidDimension(p.level()) + " participant=" + (RaidJoinHelper.getParticipant(p) != null) + " rewards=" + RaidHelper.REWARD_QUEUE.containsKey(p.getUUID()));
				})))
				.then(literal("nodamage").then(argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c -> {
					RaidRound.debugNoDamage = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "on");
					return reply(c.getSource(), "nodamage " + RaidRound.debugNoDamage);
				})))
				.then(literal("rewards").then(literal("clear").executes(c -> {
					RaidHelper.REWARD_QUEUE.clear();
					return reply(c.getSource(), "rewards cleared");
				})).executes(c -> reply(c.getSource(), "rewards " + RaidHelper.REWARD_QUEUE.keySet() + " active=" + RaidHelper.ACTIVE_RAIDS.size())))
				.then(literal("menu").then(argument("player", EntityArgument.player()).executes(c -> {
					RaidRound.onMenuRequest(EntityArgument.getPlayer(c, "player"));
					return reply(c.getSource(), "menu asked");
				})))
				.then(literal("state").executes(c -> reply(c.getSource(), state())))));
	}

	private static int reply(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal("[raidturns] " + message), true);
		return 1;
	}

	private static String crystal(ServerLevel level, BlockPos pos, String boss) {
		Block block = BuiltInRegistries.BLOCK.get(CRYSTAL);
		level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(RaidCrystalBlock.RAID_TIER, RaidTier.TIER_ONE)
				.setValue(RaidCrystalBlock.RAID_TYPE, RaidType.NORMAL).setValue(RaidCrystalBlock.CYCLE_MODE, RaidCycleMode.NONE)
				.setValue(RaidCrystalBlock.ACTIVE, true).setValue(RaidCrystalBlock.CAN_RESET, true));
		if (!(level.getBlockEntity(pos) instanceof RaidCrystalBlockEntity entity)) return "crystal: no block entity";
		entity.setRaidBoss(ResourceLocation.parse(boss), level.getGameTime());
		return "crystal " + pos.toShortString() + " boss=" + entity.getRaidBoss();
	}

	private static String enter(ServerPlayer player, BlockPos pos) {
		if (!(player.level().getBlockEntity(pos) instanceof RaidCrystalBlockEntity entity)) return "enter: no crystal";
		String block = RaidRound.entryBlock(entity.getUuid(), player.getUUID());
		if (block != null) return "enter " + player.getScoreboardName() + ": blocked " + block;
		try {
			var raid = RaidHelper.ACTIVE_RAIDS.get(entity.getUuid());
			if (raid == null) {
				var start = RaidCrystalBlock.class.getDeclaredMethod("startRaid", net.minecraft.world.entity.player.Player.class, RaidCrystalBlockEntity.class);
				start.setAccessible(true);
				return "enter " + player.getScoreboardName() + " host=" + start.invoke(player.level().getBlockState(pos).getBlock(), player, entity);
			}
			RaidJoinHelper.addParticipant(player, entity.getUuid(), false, false);
			raid.addPlayer(player);
			RaidUtils.teleportPlayerToRaid(player, player.getServer(), RaidRegionHelper.getRegion(entity.getUuid()));
			return "enter " + player.getScoreboardName() + " joined";
		} catch (ReflectiveOperationException e) {
			return "enter: " + e;
		}
	}

	private static String challenge(ServerPlayer player) {
		var participant = RaidJoinHelper.getParticipant(player);
		if (participant == null) return "r: not in a raid";
		var raid = RaidHelper.ACTIVE_RAIDS.get(participant.raid());
		if (raid == null) return "r: raid not active";
		var party = PlayerExtensionsKt.party(player).toGappyList().stream().filter(java.util.Objects::nonNull).toList();
		if (party.isEmpty()) return "r: empty party";
		new RaidChallengePacket(raid.getBossEntity().getId(), party.getFirst().getUuid(), BattleFormat.Companion.getGEN_9_SINGLES()).handleServer(player);
		return "r " + player.getScoreboardName() + " ok";
	}

	private static String act(ServerPlayer player, String move) {
		var battle = com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(player);
		if (battle == null) return "act: no battle";
		battle.getSide1().getActors()[0].setActionResponses(java.util.List.of(new com.cobblemon.mod.common.battles.MoveActionResponse(move, "p2a", null)));
		return "act " + player.getScoreboardName() + " " + move;
	}

	private static String eval(ServerPlayer player, String js) {
		var battle = com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(player);
		if (battle == null) return "eval: no battle";
		battle.writeShowdownAction(">eval " + js);
		return "eval " + player.getScoreboardName();
	}

	private static String log(ServerPlayer player) {
		var battle = com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(player);
		if (battle == null) return "log: no battle";
		var lines = new java.util.ArrayList<String>();
		for (String line : battle.getShowdownMessages())
			if (line.matches("(?s).*\\|(<<<|move|turn|cant|upkeep|-status|-curestatus|-boost|-raiddamage|-heal|-damage|faint|win)\\|?.*"))
				for (String part : line.split("\n")) if (part.replaceFirst("^\\|\\|", "|").matches("\\|(<<<|move|turn|cant|upkeep|-status|-curestatus|-boost|-raiddamage|-heal|-damage|faint|win).*")) lines.add(part.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "#"));
		return "log " + player.getScoreboardName() + " " + String.join(" ", lines);
	}

	private static String state() {
		if (RaidRound.ROUNDS.isEmpty()) return "state: no rounds";
		StringBuilder out = new StringBuilder("state:");
		RaidRound.ROUNDS.values().forEach(round -> out.append(' ').append(round.describe()));
		return out.toString();
	}
}
