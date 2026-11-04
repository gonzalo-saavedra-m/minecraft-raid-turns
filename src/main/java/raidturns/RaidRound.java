package raidturns;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.battles.BagItemActionResponse;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.DefaultActionResponse;
import com.cobblemon.mod.common.battles.FleeAttemptActionResponse;
import com.cobblemon.mod.common.battles.ForfeitActionResponse;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.necro.raid.dens.common.network.packets.RaidChallengePacket;
import com.necro.raid.dens.common.raids.RaidInstance;
import com.necro.raid.dens.common.raids.helpers.RaidHelper;
import com.necro.raid.dens.common.raids.helpers.RaidJoinHelper;
import com.necro.raid.dens.common.util.IRaidAccessor;
import com.necro.raid.dens.common.util.IRaidBattle;
import com.necro.raid.dens.common.util.IRaidTeleporter;
import com.necro.raid.dens.common.util.RaidUtils;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one raid as a single turn-based fight over Raid Dens' separate battles (one per player against a copy of the
 * boss). Each round every copy plays two Showdown turns: the one where its player acts and the one where the boss acts,
 * with the other side held by the raidturnshold condition. The player only sees the request after the second one.
 */
public final class RaidRound {
	static final int COUNTDOWN_SECONDS = 5, CHOOSE_TICKS = 60 * 20, STEP_TIMEOUT_TICKS = 30 * 20, SPEED_TIMEOUT_TICKS = 2 * 20;
	static final String HOLD = "raidturnshold";
	static final Map<UUID, RaidRound> ROUNDS = new ConcurrentHashMap<>();
	/** Pokémon each client has selected, sent by RaidTurnsClient. */
	static final Map<UUID, UUID> SELECTED = new ConcurrentHashMap<>();
	/** True while start() replays the challenge of each player, so the mixin lets it through. */
	public static boolean starting;

	public enum Phase { COUNTDOWN, WAITING_TURN, CHOOSING, READING_SPEED, STEP }
	public enum Status { WAITING, READY, ACTING, FAINTED }

	/** One slot of the round: a player or, with battle null, the boss against everyone. */
	private record Step(PokemonBattle battle, int priority, int speed) {}

	final UUID raidId;
	final RaidInstance raid;
	private final int bossEntityId;
	private final BattleFormat format;
	private final Map<UUID, UUID> startPokemon = new HashMap<>();
	private final Set<UUID> banned = new HashSet<>();
	private final Set<UUID> leaving = new HashSet<>();
	private final Map<UUID, RenderablePokemon> pokemon = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<PokemonBattle, List<ShowdownActionResponse>> choices = new HashMap<>();
	private final Map<PokemonBattle, Integer> turnsDone = new HashMap<>();
	private final Set<PokemonBattle> internal = ConcurrentHashMap.newKeySet();
	private final Map<PokemonBattle, int[]> speeds = new ConcurrentHashMap<>();
	private final Set<PokemonBattle> stepWaiting = new HashSet<>();
	private final List<Step> steps = new ArrayList<>();

	private Phase phase = Phase.COUNTDOWN;
	private int ticks;
	private boolean started, bossSlept, bossPhase, allowDispatch;
	private PokemonBattle actingBattle, bossPhaseFirst;
	private ShowdownActionResponse bossMove;
	private String lastState = "", lastOrder = "";
	/** Only set by /raidturns bossmove. */
	static String debugBossMove;
	/** Only set by /raidturns nodamage: the raid does not end by itself during tests. */
	public static boolean debugNoDamage;

	private RaidRound(UUID raidId, RaidInstance raid, int bossEntityId, BattleFormat format) {
		this.raidId = raidId;
		this.raid = raid;
		this.bossEntityId = bossEntityId;
		this.format = format;
	}

	/** A player pressed R on a raid boss. Returns true if this mod took over the challenge. */
	public static boolean onChallenge(RaidChallengePacket packet, ServerPlayer player) {
		if (starting || !RaidUtils.isRaidDimension(player.level())) return false;
		if (!(player.level().getEntity(packet.targetedEntityId()) instanceof PokemonEntity boss)) return false;
		IRaidAccessor accessor = (IRaidAccessor) boss;
		if (!accessor.crd_isRaidBoss()) return false;
		UUID raidId = accessor.crd_getRaidId();
		RaidInstance raid = RaidHelper.ACTIVE_RAIDS.get(raidId);
		if (raid == null) return false;
		RaidRound round = ROUNDS.computeIfAbsent(raidId, id -> new RaidRound(id, raid, packet.targetedEntityId(), packet.battleFormat()));
		if (!round.started) round.startPokemon.put(player.getUUID(), packet.selectedPokemonId());
		else player.sendSystemMessage(Component.translatable("raidturns.closed"), true);
		return true;
	}

	public static RaidRound of(PokemonBattle battle) {
		RaidInstance raid = ((IRaidBattle) battle).crd_getRaidBattle();
		return raid == null ? null : of(raid);
	}

	public static RaidRound of(RaidInstance raid) {
		for (RaidRound round : ROUNDS.values()) if (round.raid == raid) return round;
		return null;
	}

	public static void tickAll(MinecraftServer server) {
		ROUNDS.values().removeIf(round -> round.tick(server));
	}

	/** The message that stops this player from entering the raid, or null if they can enter. */
	public static String entryBlock(UUID raidId, UUID player) {
		RaidRound round = ROUNDS.get(raidId);
		if (round == null) return null;
		return round.banned.contains(player) ? "raidturns.banned" : "raidturns.closed";
	}

	private boolean tick(MinecraftServer server) {
		handleLeaving(server);
		if (raid.isFinished() || RaidHelper.ACTIVE_RAIDS.get(raidId) != raid) {
			if (!leaving.isEmpty()) return false;
			broadcastEnd(server);
			return true;
		}
		ticks++;
		switch (phase) {
			case COUNTDOWN -> tickCountdown(server);
			case WAITING_TURN -> tickWaitingTurn();
			case CHOOSING -> tickChoosing();
			case READING_SPEED -> tickReadingSpeed();
			case STEP -> tickStep();
		}
		sendState(server);
		return false;
	}

	private void tickCountdown(MinecraftServer server) {
		if (ticks % 20 == 1) {
			int left = COUNTDOWN_SECONDS - ticks / 20;
			for (ServerPlayer player : group(server)) {
				player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 25, 5));
				player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("raidturns.countdown")));
				player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(String.valueOf(left))));
			}
		}
		if (ticks >= COUNTDOWN_SECONDS * 20) start(server);
	}

	private List<ServerPlayer> group(MinecraftServer server) {
		List<ServerPlayer> group = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers())
			if (RaidUtils.isRaidDimension(player.level()) && RaidJoinHelper.isParticipating(player, raidId)) group.add(player);
		return group;
	}

	private void start(MinecraftServer server) {
		started = true;
		starting = true;
		try {
			for (ServerPlayer player : group(server)) {
				Pokemon lead = chooseLead(player);
				if (lead == null) continue;
				new RaidChallengePacket(bossEntityId, lead.getUuid(), format).handleServer(player);
				pokemon.put(player.getUUID(), lead.asRenderablePokemon());
				names.put(player.getUUID(), player.getScoreboardName());
			}
		} finally {
			starting = false;
		}
		begin(Phase.WAITING_TURN);
	}

	/** The Pokémon they pressed R with, else the one selected in their client, else the first alive in their party. */
	private Pokemon chooseLead(ServerPlayer player) {
		var party = PlayerExtensionsKt.party(player);
		for (UUID id : new UUID[]{startPokemon.get(player.getUUID()), SELECTED.get(player.getUUID())}) {
			Pokemon selected = id == null ? null : party.get(id);
			if (selected != null && !selected.isFainted()) return selected;
		}
		for (Pokemon member : party) if (!member.isFainted()) return member;
		return null;
	}

	private void begin(Phase next) {
		phase = next;
		ticks = 0;
	}

	private List<PokemonBattle> live() {
		List<PokemonBattle> live = new ArrayList<>();
		for (PokemonBattle battle : raid.getBattles()) if (!battle.getEnded()) live.add(battle);
		return live;
	}

	private void tickWaitingTurn() {
		List<PokemonBattle> live = live();
		if (live.isEmpty()) return;
		for (PokemonBattle battle : live) if (!ready(battle)) return;
		choices.clear();
		turnsDone.clear();
		speeds.clear();
		begin(Phase.CHOOSING);
	}

	private void tickChoosing() {
		List<PokemonBattle> live = live();
		if (live.isEmpty()) return;
		boolean timedOut = ticks >= CHOOSE_TICKS;
		boolean ready = true;
		for (PokemonBattle battle : live) {
			BattleActor actor = player(battle);
			if (!actor.getResponses().isEmpty()) continue;
			if (timedOut) actor.setResponses(new ArrayList<>(List.of(new DefaultActionResponse())));
			else ready = false;
		}
		if (!ready) return;
		for (PokemonBattle battle : live) {
			choices.put(battle, List.copyOf(player(battle).getResponses()));
			battle.writeShowdownAction(">eval battle.add('rtspeed', battle.sides[0].active[0].getActionSpeed(), battle.sides[1].active[0].getActionSpeed());");
		}
		begin(Phase.READING_SPEED);
	}

	/** Called from the rtspeed instruction, on the Showdown side of the dispatch. */
	static void onSpeeds(PokemonBattle battle, int playerSpeed, int bossSpeed) {
		RaidRound round = of(battle);
		if (round != null) round.speeds.put(battle, new int[]{playerSpeed, bossSpeed});
	}

	private void tickReadingSpeed() {
		List<PokemonBattle> live = live();
		if (live.isEmpty()) return;
		if (!speeds.keySet().containsAll(live) && ticks < SPEED_TIMEOUT_TICKS) return;
		PokemonBattle first = live.getFirst();
		bossMove = boss(first).getResponses().isEmpty() ? new DefaultActionResponse() : boss(first).getResponses().getFirst();
		steps.clear();
		steps.add(new Step(null, priorityOf(bossMove), speeds.getOrDefault(first, new int[2])[1]));
		for (PokemonBattle battle : live) {
			List<ShowdownActionResponse> choice = choices.getOrDefault(battle, List.of());
			steps.add(new Step(battle, choice.isEmpty() ? 0 : priorityOf(choice.getFirst()), speeds.getOrDefault(battle, new int[2])[0]));
		}
		steps.sort(Comparator.comparingInt(Step::priority).thenComparingInt(Step::speed).reversed());
		if (debugBossMove != null) bossMove = new MoveActionResponse(debugBossMove, null, null);
		lastOrder = steps.stream().map(step -> (step.battle() == null ? "boss" : player(step.battle()).getName().getString()) + ":" + step.priority() + ":" + step.speed()).toList().toString();
		nextStep();
	}

	private static int priorityOf(ShowdownActionResponse response) {
		if (response instanceof BagItemActionResponse) return 7;
		if (response instanceof MoveActionResponse move) {
			MoveTemplate template = Moves.getByName(move.getMoveName());
			return template == null ? 0 : template.getPriority();
		}
		return 0;
	}

	private void nextStep() {
		syncSleep();
		bossPhase = false;
		actingBattle = null;
		bossPhaseFirst = null;
		// Steps whose player left, fainted or fled are skipped
		while (!steps.isEmpty()) {
			Step step = steps.removeFirst();
			List<PokemonBattle> live = live();
			if (live.isEmpty()) return;
			stepWaiting.clear();
			if (step.battle() == null) {
				bossPhase = true;
				bossPhaseFirst = live.getFirst();
				for (PokemonBattle battle : live) run(battle, false);
			} else {
				if (!live.contains(step.battle())) continue;
				actingBattle = step.battle();
				run(step.battle(), true);
			}
			begin(Phase.STEP);
			return;
		}
		begin(Phase.WAITING_TURN);
	}

	/** Plays one Showdown turn of a copy: the player's choice and the boss held, or the boss's move and the player held. */
	private void run(PokemonBattle battle, boolean playerActs) {
		int done = turnsDone.merge(battle, 1, Integer::sum);
		// The request after the copy's first turn of the round is answered here; the one after the second goes to the client
		if (done == 1) internal.add(battle);
		else internal.remove(battle);
		BattleActor player = player(battle), boss = boss(battle);
		battle.writeShowdownAction(">eval " + prelude(playerActs ? 1 : 0, done == 2));
		allowDispatch = true;
		try {
			// Cobblemon dispatches once no actor has mustChoose left
			boss.setResponses(new ArrayList<>(List.of(playerActs || !canUse(boss, bossMove) ? new DefaultActionResponse() : bossMove)));
			boss.setMustChoose(false);
			List<ShowdownActionResponse> choice = playerActs ? choices.getOrDefault(battle, List.of()) : List.of();
			if (!choice.isEmpty() && done == 2) {
				// Validated against the new request: the boss may have disabled the chosen move
				player.getResponses().clear();
				player.setActionResponses(new ArrayList<>(choice));
				if (!player.getMustChoose()) return;
				choice = List.of();
			}
			player.setResponses(new ArrayList<>(choice.isEmpty() ? List.of(new DefaultActionResponse()) : choice));
			player.setMustChoose(false);
			battle.checkForInputDispatch();
		} finally {
			allowDispatch = false;
			stepWaiting.add(battle);
		}
	}

	/** A move chosen in another copy may be disabled in this one (Disable, Encore, Torment). */
	private static boolean canUse(BattleActor actor, ShowdownActionResponse response) {
		if (!(response instanceof MoveActionResponse move) || actor.getRequest() == null) return true;
		var active = actor.getRequest().getActive();
		if (active == null || active.isEmpty()) return false;
		return active.getFirst().getMoves().stream().anyMatch(known -> known.getId().equals(move.getMoveName()) && !known.getDisabled());
	}

	/** Holds one side and leaves end-of-turn effects for the copy's last turn, so they count once per round. */
	private static String prelude(int heldSide, boolean lastTurn) {
		return "for (const s of battle.sides) { const p = s.active[0]; if (p) { p.removeVolatile('" + HOLD + "'); p.moveLastTurnResult = true; } } "
				+ "battle.sides[" + heldSide + "].active[0].addVolatile('" + HOLD + "'); "
				+ (lastTurn ? "delete battle.residualEvent;" : "battle.residualEvent = function () {};");
	}

	private void tickStep() {
		stepWaiting.removeIf(battle -> battle.getEnded() || !raid.getBattles().contains(battle) || ready(battle));
		if (stepWaiting.isEmpty()) {
			nextStep();
			return;
		}
		if (ticks >= STEP_TIMEOUT_TICKS) {
			RaidTurns.LOGGER.warn("Raid {}: a turn did not finish in time, answering with the default move", raidId);
			for (PokemonBattle battle : stepWaiting) battle.writeShowdownAction(">p1 default", ">p2 default");
			ticks = 0;
		}
	}

	/** Every copy rolled its own sleep counter: overwrites them with a single one, so the boss wakes up in all at once. */
	private void syncSleep() {
		var status = ((raidturns.mixin.RaidPokemonAccessor) raid.getBattleState().bossSide.pokemon).raidturns$status();
		boolean asleep = status != null && "slp".equals(status.getName().getPath()) || status != null && "sleep".equals(status.getName().getPath());
		if (asleep && !bossSlept) {
			int time = 2 + new java.util.Random().nextInt(4);
			for (PokemonBattle battle : live())
				battle.writeShowdownAction(">eval { const p = battle.sides[1].active[0]; if (p && p.status === 'slp') { p.statusState.time = " + time + "; p.statusState.startTime = " + time + "; } }");
		}
		bossSlept = asleep;
	}

	/** Mixin on checkForInputDispatch: true means Cobblemon may send the choices of this battle to Showdown now. */
	public boolean canDispatch(PokemonBattle battle) {
		if (allowDispatch || !raid.getBattles().contains(battle)) return true;
		BattleActor player = player(battle);
		for (ShowdownActionResponse response : player.getResponses()) {
			if (response instanceof ForfeitActionResponse || response instanceof FleeAttemptActionResponse) {
				if (player instanceof PlayerBattleActor actor) leaving.add(actor.getUuid());
				return true;
			}
		}
		return false;
	}

	private void handleLeaving(MinecraftServer server) {
		for (UUID id : new ArrayList<>(leaving)) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null && com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(player) != null) continue;
			leaving.remove(id);
			if (player == null) continue;
			// Alone, their battle ending already closed the raid: they go home and can start it again
			if (raid.isFinished() || RaidHelper.ACTIVE_RAIDS.get(raidId) != raid) {
				RaidJoinHelper.removeParticipant(player);
				if (RaidUtils.isRaidDimension(player.level())) ((IRaidTeleporter) player).crd_returnHome();
				continue;
			}
			banned.add(id);
			pokemon.remove(id);
			names.remove(id);
			raid.getPlayers().remove(player);
			raid.removeFromBossEvent(player);
			RaidJoinHelper.removeParticipant(player);
			if (RaidUtils.isRaidDimension(player.level())) ((IRaidTeleporter) player).crd_returnHome();
			ServerPlayNetworking.send(player, new GroupStatePayload(GroupStatePayload.NONE, 0, List.of()));
		}
	}

	/** Mixin on PlayerBattleActor.sendUpdate: true hides requests of a turn the server answers by itself. */
	public boolean isInternal(PokemonBattle battle) {
		return internal.contains(battle);
	}

	/** Mixin on the sync of battle state: in the boss's turn each copy runs the move, so copying it would apply it twice. */
	public boolean isBossPhase() {
		return bossPhase;
	}

	/** A boss healing in its turn runs once per copy: only the first one counts. */
	public boolean ignoresBossHeal(PokemonBattle battle) {
		return bossPhase && battle != bossPhaseFirst;
	}

	private void sendState(MinecraftServer server) {
		int phaseId = switch (phase) {
			case COUNTDOWN -> GroupStatePayload.NONE;
			case WAITING_TURN, READING_SPEED, STEP -> GroupStatePayload.RESOLVING;
			case CHOOSING -> GroupStatePayload.CHOOSING;
		};
		List<GroupStatePayload.Member> members = new ArrayList<>();
		Map<UUID, PokemonBattle> battles = new HashMap<>();
		for (PokemonBattle battle : live()) if (player(battle) instanceof PlayerBattleActor actor) battles.put(actor.getUuid(), battle);
		for (var entry : names.entrySet()) {
			PokemonBattle battle = battles.get(entry.getKey());
			Status status = battle == null ? Status.FAINTED
					: battle == actingBattle ? Status.ACTING
					: phase == Phase.CHOOSING && player(battle).getResponses().isEmpty() ? Status.WAITING : Status.READY;
			members.add(new GroupStatePayload.Member(entry.getKey(), entry.getValue(), pokemon.get(entry.getKey()), status.ordinal()));
		}
		int seconds = phase == Phase.CHOOSING ? Math.max(0, (CHOOSE_TICKS - ticks + 19) / 20) : 0;
		StringBuilder key = new StringBuilder().append(phaseId);
		for (var member : members) key.append(member.id()).append(member.status());
		boolean changed = !key.toString().equals(lastState);
		boolean secondTick = phase == Phase.CHOOSING && ticks % 20 == 0;
		if (!changed && !secondTick) return;
		lastState = key.toString();
		long missing = members.stream().filter(member -> member.status() == Status.WAITING.ordinal()).count();
		for (var member : members) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id());
			if (player == null) continue;
			if (ServerPlayNetworking.canSend(player, GroupStatePayload.TYPE)) {
				if (changed) ServerPlayNetworking.send(player, new GroupStatePayload(phaseId, seconds, members));
			} else if (secondTick && missing > 0) {
				player.sendSystemMessage(missing == 1
						? Component.translatable("raidturns.waiting.one", seconds)
						: Component.translatable("raidturns.waiting.many", missing, seconds), true);
			}
		}
	}

	private void broadcastEnd(MinecraftServer server) {
		for (UUID id : names.keySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null && ServerPlayNetworking.canSend(player, GroupStatePayload.TYPE))
				ServerPlayNetworking.send(player, new GroupStatePayload(GroupStatePayload.NONE, 0, List.of()));
		}
	}

	/** The copy finished its turn and its animations, and has a new request. */
	private static boolean ready(PokemonBattle battle) {
		BattleActor player = player(battle);
		// A player may have chosen already if their copy was ready before the others
		return (player.getMustChoose() || !player.getResponses().isEmpty()) && battle.getDispatches().isEmpty();
	}

	private static BattleActor player(PokemonBattle battle) {
		return battle.getSide1().getActors()[0];
	}

	private static BattleActor boss(PokemonBattle battle) {
		return battle.getSide2().getActors()[0];
	}

	/** State of the round for /raidturns state. */
	String describe() {
		StringBuilder out = new StringBuilder(phase + " ticks=" + ticks + " started=" + started + " bossPhase=" + bossPhase + " banned=" + banned.size() + " order=" + lastOrder + " players=" + raid.getPlayers().size() + " state=" + lastState.replaceAll("[0-9a-f-]{36}", ":"));
		for (PokemonBattle battle : live()) {
			BattleActor actor = player(battle);
			out.append(" | ").append(actor.getName().getString()).append(" must=").append(actor.getMustChoose())
					.append(" resp=").append(actor.getResponses().size()).append(" internal=").append(isInternal(battle))
					.append(" team=").append(actor.getPokemonList().size()).append(" turn=").append(battle.getTurn());
		}
		return out.toString();
	}
}
