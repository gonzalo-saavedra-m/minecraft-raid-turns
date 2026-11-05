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
import com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattlePersistentStatusPacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.status.PersistentStatus;
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
import net.minecraft.network.chat.contents.TranslatableContents;
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
 * with the other side held by the raidturnshold condition. The player only sees a move menu when the round asks for it.
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
	public enum Status { WAITING, READY, ACTING, FAINTED, BOSS }

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
	private final Map<UUID, Pokemon> leads = new HashMap<>();
	/** What each copy's Showdown last reported through |rtview|: player's status, player's boosts, boss's boosts. */
	private final Map<PokemonBattle, String[]> views = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<PokemonBattle, List<ShowdownActionResponse>> choices = new HashMap<>();
	private final Map<PokemonBattle, Integer> turnsDone = new HashMap<>();
	private final Map<PokemonBattle, int[]> speeds = new ConcurrentHashMap<>();
	private final Set<PokemonBattle> stepWaiting = new HashSet<>();
	private final List<Step> steps = new ArrayList<>();
	/** Last health Cobblemon reported for each copy's player, to tell each change apart. */
	private final Map<PokemonBattle, Float> lastHealth = new HashMap<>();
	private final Map<PokemonBattle, String> statusShown = new HashMap<>();
	private final List<String> recentLog = new ArrayList<>();
	/** Round numbers each copy's log has shown, for /raidturns state. */
	private final Map<PokemonBattle, List<Integer>> roundsShown = new HashMap<>();
	/** The copy each player without a battle is watching, and who closed that view with Esc. */
	private final Map<UUID, PokemonBattle> watching = new HashMap<>();
	private final Set<UUID> stoppedWatching = new HashSet<>();

	private Phase phase = Phase.COUNTDOWN;
	private int ticks;
	private boolean started, bossSlept, bossPhase, allowDispatch, sendingMenu, showingBossHealth, saying, bossResidualDone;
	private int menusSent, bossHealthHidden, bossHealthSent, linesRelayed;
	private PokemonBattle actingBattle, bossPhaseFirst;
	private ShowdownActionResponse bossMove;
	private String lastState = "", lastOrder = "", lastPanel = "";
	/** The last order the moves ran in, which the panel follows; empty until the first round is resolved. */
	private final List<UUID> turnOrder = new ArrayList<>();
	/** Only set by /raidturns bossmove. */
	static String debugBossMove;
	/** Only set by /raidturns nodamage: the raid does not end by itself during tests. */
	public static boolean debugNoDamage;
	/** Only read by /raidturns state: how the last raid ended. */
	static String debugLastEnd = "none";
	/** Only read by /raidturns chat: the log lines each player's client was sent. */
	private final Map<UUID, List<String>> debugChat = new ConcurrentHashMap<>();

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
		ROUNDS.values().removeIf(round -> {
			try {
				return round.tick(server);
			} catch (RuntimeException e) {
				// A raid that breaks loses its turns, not the whole server
				RaidTurns.LOGGER.error("Raid {}: stopping turns after an error", round.raidId, e);
				round.broadcastEnd(server);
				return true;
			}
		});
	}

	/** The message that stops this player from entering the raid, or null if they can enter. */
	public static String entryBlock(UUID raidId, UUID player) {
		RaidRound round = ROUNDS.get(raidId);
		if (round == null) return null;
		return round.banned.contains(player) ? "raidturns.banned" : "raidturns.closed";
	}

	/** Takes a disconnected player out of a raid in progress. Returns false if no round has started for them. */
	public static boolean onDisconnect(ServerPlayer player) {
		var participant = RaidJoinHelper.getParticipant(player);
		RaidRound round = participant == null ? null : ROUNDS.get(participant.raid());
		if (round == null || !round.started) return false;
		round.removeDisconnected(player);
		return true;
	}

	private void removeDisconnected(ServerPlayer player) {
		UUID id = player.getUUID();
		PokemonBattle battle = null;
		for (PokemonBattle candidate : raid.getBattles())
			if (player(candidate) instanceof PlayerBattleActor actor && id.equals(actor.getUuid())) battle = candidate;
		leaving.remove(id);
		banned.add(id);
		pokemon.remove(id);
		leads.remove(id);
		names.remove(id);
		// Stops the raid as lost if this was the last one who hadn't failed
		raid.removePlayer(player, battle, true);
		raid.getPlayers().remove(player);
		raid.removeFromBossEvent(player);
		RaidJoinHelper.removeParticipant(player);
		if (raid.getPlayers().isEmpty() && !raid.isFinished()) raid.stopRaid(false);
	}

	public static void dropParticipant(ServerPlayer player) {
		var participant = RaidJoinHelper.getParticipant(player);
		if (participant == null) return;
		RaidInstance raid = RaidHelper.ACTIVE_RAIDS.get(participant.raid());
		if (raid != null) {
			raid.getPlayers().remove(player);
			raid.removeFromBossEvent(player);
		}
		RaidJoinHelper.removeParticipant(player);
	}

	private boolean tick(MinecraftServer server) {
		handleLeaving(server);
		if (raid.isFinished() || RaidHelper.ACTIVE_RAIDS.get(raidId) != raid) {
			if (!leaving.isEmpty()) return false;
			debugLastEnd = raid.getRaidState().name();
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
				leads.put(player.getUUID(), lead);
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
		publishBoss();
		for (PokemonBattle battle : live) {
			lastHealth.putIfAbsent(battle, (float) lead(battle).getEffectedPokemon().getCurrentHealth());
			if (player(battle).getResponses().isEmpty()) sendMenu(battle);
		}
	}

	private void tickChoosing() {
		List<PokemonBattle> live = live();
		if (live.isEmpty()) return;
		// A fainted player's copy ends after the round's last step, so this can't wait for the next one
		if (ticks % 20 == 1 && watchFainted()) publishBoss();
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
		bossResidualDone = false;
		steps.clear();
		steps.add(new Step(null, priorityOf(bossMove), speeds.getOrDefault(first, new int[2])[1]));
		for (PokemonBattle battle : live) {
			List<ShowdownActionResponse> choice = choices.getOrDefault(battle, List.of());
			steps.add(new Step(battle, choice.isEmpty() ? 0 : priorityOf(choice.getFirst()), speeds.getOrDefault(battle, new int[2])[0]));
		}
		steps.sort(Comparator.comparingInt(Step::priority).thenComparingInt(Step::speed).reversed());
		if (debugBossMove != null) bossMove = new MoveActionResponse(debugBossMove, null, null);
		turnOrder.clear();
		for (Step step : steps) {
			if (step.battle() == null) turnOrder.add(raid.getBossEntity().getUUID());
			else if (player(step.battle()) instanceof PlayerBattleActor actor) turnOrder.add(actor.getUuid());
		}
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
		publishBoss();
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

	/**
	 * A player whose copy ended keeps watching a teammate's copy, which gets the shared log and the boss's real state.
	 * Returns true if someone started watching: what Cobblemon sends them first is the copy's own view of the boss.
	 */
	private boolean watchFainted() {
		List<PokemonBattle> live = live();
		if (live.isEmpty() || !(player(live.getFirst()) instanceof PlayerBattleActor target)) return false;
		ServerPlayer targetPlayer = PlayerExtensionsKt.getPlayer(target.getUuid());
		if (targetPlayer == null) return false;
		boolean started = false;
		for (UUID id : names.keySet()) {
			ServerPlayer player = PlayerExtensionsKt.getPlayer(id);
			if (player == null || stoppedWatching.contains(id) || !RaidUtils.isRaidDimension(player.level()) || com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(player) != null) continue;
			PokemonBattle watched = watching.get(id);
			if (watched != null && live.contains(watched)) {
				if (!watched.getSpectators().contains(id)) stoppedWatching.add(id);
				continue;
			}
			com.cobblemon.mod.common.net.serverhandling.battle.SpectateBattleHandler.INSTANCE.spectateBattle(targetPlayer, player);
			watching.put(id, live.getFirst());
			started = true;
		}
		return started;
	}

	/** Called from the rtview instruction, on the Showdown side of the dispatch. */
	static void onView(PokemonBattle battle, String ailment, String boosts, String bossBoosts) {
		RaidRound round = of(battle);
		if (round != null) round.views.put(battle, new String[]{ailment, boosts, bossBoosts});
	}

	/** Mixin on syncHealth: a positive amount is damage. Runs per event, so a burn does not get mixed with the hit before it. */
	public void onBossHealth(PokemonBattle battle, float amount) {
		publishBossHealth();
		if (amount == 0) return;
		say(amount > 0 ? "raidturns.log.boss_damaged" : "raidturns.log.boss_healed", boss(battle).getPokemonList().getFirst().getName(), percent(Math.abs(amount), raid.getMaxHealth()));
	}

	/** Mixin on PlayerBattleActor.sendUpdate: the health Cobblemon just reported for this copy's player. */
	public void onPlayerHealth(PokemonBattle battle, float health) {
		Float last = lastHealth.put(battle, health);
		if (last == null || last == health) return;
		Pokemon pokemon = lead(battle).getEffectedPokemon();
		say(health < last ? "raidturns.log.player_damaged" : "raidturns.log.player_healed", lead(battle).getName(), owner(battle), percent(Math.abs(last - health), pokemon.getMaxHealth()));
	}

	/** Mixins on the packets that carry log lines. Bounded, since it runs on the real server too. */
	public void recordChat(UUID player, List<Component> messages) {
		List<String> lines = debugChat.computeIfAbsent(player, ignored -> new ArrayList<>());
		for (Component message : messages) {
			if (lines.size() >= 200) lines.removeFirst();
			lines.add(message.getContents() instanceof TranslatableContents contents ? contents.getKey() + java.util.Arrays.stream(contents.getArgs()).map(arg -> arg instanceof Component c ? c.getString() : String.valueOf(arg)).toList() : message.getString());
		}
	}

	List<String> chatOf(UUID player) {
		return debugChat.getOrDefault(player, List.of());
	}

	private void say(String key, Object... args) {
		Component line = Component.translatable(key, args);
		saying = true;
		for (PokemonBattle battle : live()) battle.broadcastChatMessage(line);
		saying = false;
		remember(key.substring(key.lastIndexOf('.') + 1) + ":" + args[args.length - 1]);
	}

	/** Mixin on broadcastChatMessage: the log just showed this round number. */
	public void onRoundShown(PokemonBattle battle, int number) {
		roundsShown.computeIfAbsent(battle, ignored -> new ArrayList<>()).add(number);
	}

	private void remember(String line) {
		if (recentLog.size() >= 30) recentLog.removeFirst();
		recentLog.add(line);
	}

	private static int percent(float lost, float max) {
		return Math.max(1, Math.round(lost * 100 / max));
	}

	private static com.cobblemon.mod.common.battles.pokemon.BattlePokemon lead(PokemonBattle battle) {
		return player(battle).getPokemonList().getFirst();
	}

	private String owner(PokemonBattle battle) {
		return player(battle) instanceof PlayerBattleActor actor ? names.getOrDefault(actor.getUuid(), "") : "";
	}

	/**
	 * Mixin on broadcastChatMessage: what one copy logs, the others never ran. That is everything in its player's turn. In
	 * the boss's turn all copies run the same move, so only lines about its own player: burn, Leftovers, fainting.
	 */
	public void relayLog(PokemonBattle battle, Component message) {
		if (saying || battle != actingBattle && !(bossPhase && isAboutPlayer(battle, message))) return;
		Component line = Component.literal(owner(battle) + ": ").append(message);
		for (PokemonBattle other : live()) {
			if (other == battle) continue;
			other.broadcastChatMessage(line);
			linesRelayed++;
		}
		if (message.getContents() instanceof TranslatableContents contents) remember(contents.getKey());
	}

	/** shortcut: compares names, so a player with the boss's species is not relayed in the boss's turn; good enough for a log line. */
	private static boolean isAboutPlayer(PokemonBattle battle, Component message) {
		if (!(message.getContents() instanceof TranslatableContents contents)) return false;
		String own = lead(battle).getName().getString(), bossName = boss(battle).getPokemonList().getFirst().getName().getString();
		boolean about = false;
		for (Object arg : contents.getArgs()) {
			if (!(arg instanceof Component name)) continue;
			if (name.getString().equals(bossName)) return false;
			about |= name.getString().equals(own);
		}
		return about;
	}

	/** The copies only calculate: what clients show of the boss always comes from the raid, the one place that has it right. */
	private void publishBoss() {
		publishBossHealth();
		PersistentStatus status = bossStatus() instanceof PersistentStatus persistent ? persistent : null;
		for (PokemonBattle battle : live()) {
			battle.sendUpdate(new BattlePersistentStatusPacket(boss(battle).getActivePokemon().getFirst().getPNX(), status));
			statusShown.put(battle, status == null ? "none" : status.getShowdownName());
			battle.writeShowdownAction(">eval { const f = p => Object.entries(p.boosts).filter(e => e[1]).map(e => e[0] + ':' + e[1]).join(',') || '-'; const a = battle.sides[0].active[0], b = battle.sides[1].active[0]; if (a && b) battle.add('rtview', a.status || '-', f(a), f(b)); }");
		}
	}

	private void publishBossHealth() {
		for (PokemonBattle battle : live()) ((raidturns.mixin.RaidInstanceAccessor) raid).raidturns$sendHealth(battle);
	}

	private com.cobblemon.mod.common.api.pokemon.status.Status bossStatus() {
		return ((raidturns.mixin.RaidPokemonAccessor) raid.getBattleState().bossSide.pokemon).raidturns$status();
	}

	/** Plays one Showdown turn of a copy: the player's choice and the boss held, or the boss's move and the player held. */
	private void run(PokemonBattle battle, boolean playerActs) {
		int done = turnsDone.merge(battle, 1, Integer::sum);
		BattleActor player = player(battle), boss = boss(battle);
		// Every copy ends the round with the boss's poison or Leftovers: only the first one counts, like in a single battle
		boolean bossResidual = done == 2 && !bossResidualDone;
		bossResidualDone |= bossResidual;
		battle.writeShowdownAction(">eval " + prelude(playerActs ? 1 : 0, done == 2, bossResidual));
		allowDispatch = true;
		try {
			// Cobblemon dispatches once no actor has mustChoose left
			boss.setResponses(new ArrayList<>(List.of(playerActs || !canUse(boss, bossMove) ? new DefaultActionResponse() : bossMove)));
			boss.setMustChoose(false);
			List<ShowdownActionResponse> choice = playerActs ? choices.getOrDefault(battle, List.of()) : List.of();
			if (!choice.isEmpty() && done == 2) {
				// Validated against the new request: the boss may have disabled the chosen move
				player.getResponses().clear();
				try {
					player.setActionResponses(new ArrayList<>(choice));
					if (!player.getMustChoose()) return;
				} catch (com.cobblemon.mod.common.exception.IllegalActionChoiceException ignored) {
					// Throws rather than returning: the move is disabled, or the request changed since they chose
				}
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
	private static String prelude(int heldSide, boolean lastTurn, boolean bossResidual) {
		return "for (const s of battle.sides) { const p = s.active[0]; if (p) { p.removeVolatile('" + HOLD + "'); p.moveLastTurnResult = true; } } "
				+ "battle.sides[" + heldSide + "].active[0].addVolatile('" + HOLD + "'); "
				+ (!lastTurn ? "battle.residualEvent = function () {};"
				: bossResidual ? "delete battle.residualEvent;"
				: "battle.residualEvent = function (id, relay) { const b = this.sides[1].active[0]; if (b) b.addVolatile('raidturnsquiet'); Object.getPrototypeOf(this).residualEvent.call(this, id, relay); if (b) b.removeVolatile('raidturnsquiet'); };");
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
		var status = bossStatus();
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
			leads.remove(id);
			names.remove(id);
			raid.getPlayers().remove(player);
			raid.removeFromBossEvent(player);
			RaidJoinHelper.removeParticipant(player);
			if (RaidUtils.isRaidDimension(player.level())) ((IRaidTeleporter) player).crd_returnHome();
			ServerPlayNetworking.send(player, new GroupStatePayload(GroupStatePayload.NONE, 0, List.of()));
		}
	}

	/**
	 * Mixin on PlayerBattleActor.sendUpdate: the move menu opens only through sendMenu. Cobblemon ignores a choice while the
	 * server's mustChoose is false, and its request reaches the client before that, so an early choice would be lost.
	 */
	public boolean hidesMenu() {
		return !sendingMenu;
	}

	public boolean hidesBossHealth() {
		bossHealthHidden += showingBossHealth ? 0 : 1;
		return !showingBossHealth;
	}

	public void showBossHealth(boolean showing) {
		showingBossHealth = showing;
		if (showing) bossHealthSent++;
	}

	private void sendMenu(PokemonBattle battle) {
		BattleActor actor = player(battle);
		if (actor.getRequest() == null) return;
		actor.setMustChoose(true);
		sendingMenu = true;
		try {
			actor.sendUpdate(new BattleQueueRequestPacket(actor.getRequest()));
			actor.sendUpdate(new BattleMakeChoicePacket());
			menusSent++;
		} finally {
			sendingMenu = false;
		}
	}

	/** A client whose panel says it is missing but has no menu asks for it again. */
	static void onMenuRequest(ServerPlayer player) {
		var battle = com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(player);
		RaidRound round = battle == null ? null : of(battle);
		if (round != null && round.phase == Phase.CHOOSING && round.raid.getBattles().contains(battle) && player(battle).getResponses().isEmpty())
			round.sendMenu(battle);
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
			Pokemon lead = leads.get(entry.getKey());
			String[] view = battle == null ? null : views.get(battle);
			members.add(new GroupStatePayload.Member(entry.getKey(), entry.getValue(), pokemon.get(entry.getKey()), status.ordinal(),
					lead == null || lead.getMaxHealth() == 0 ? 0 : (float) lead.getCurrentHealth() / lead.getMaxHealth(),
					view == null ? "-" : view[0], view == null ? "-" : view[1]));
		}
		if (!members.isEmpty()) {
			String[] bossView = live().stream().map(views::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
			var ailment = bossStatus();
			members.addFirst(new GroupStatePayload.Member(raid.getBossEntity().getUUID(), "", raid.getBossEntity().getPokemon().asRenderablePokemon(), Status.BOSS.ordinal(),
					raid.getCurrentHealth() / raid.getMaxHealth(), ailment == null ? "-" : ailment.getShowdownName(), bossView == null ? "-" : bossView[2]));
		}
		// Whoever has no place in the order (fainted, or the first round) goes last, and the sort keeps the boss on top of those
		members.sort(Comparator.comparingInt(member -> turnOrder.contains(member.id()) ? turnOrder.indexOf(member.id()) : Integer.MAX_VALUE));
		lastPanel = members.stream().map(member -> member.status() == Status.BOSS.ordinal() ? "boss" : member.name()).toList().toString();
		int seconds = phase == Phase.CHOOSING ? Math.max(0, (CHOOSE_TICKS - ticks + 19) / 20) : 0;
		StringBuilder key = new StringBuilder().append(phaseId);
		for (var member : members) key.append(member.id()).append(member.status()).append(Math.round(member.health() * 100)).append(member.ailment()).append(member.boosts());
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
		StringBuilder out = new StringBuilder(phase + " ticks=" + ticks + " started=" + started + " bossPhase=" + bossPhase + " banned=" + banned.size() + " menus=" + menusSent + " healthSent=" + bossHealthSent + " healthHidden=" + bossHealthHidden + " relayed=" + linesRelayed + " bossLog=" + recentLog + " health=" + raid.getCurrentHealth() + "/" + raid.getMaxHealth() + " order=" + lastOrder + " panel=" + lastPanel + " players=" + raid.getPlayers().size() + " state=" + lastState.replaceAll("[0-9a-f-]{36}", ":"));
		for (PokemonBattle battle : live()) {
			BattleActor actor = player(battle);
			out.append(" | ").append(actor.getName().getString()).append(" must=").append(actor.getMustChoose())
					.append(" resp=").append(actor.getResponses().size())
					.append(" team=").append(actor.getPokemonList().size()).append(" turn=").append(battle.getTurn()).append(" shown=").append(statusShown.get(battle)).append(" spectators=").append(battle.getSpectators().size()).append(" view=").append(String.join("/", views.getOrDefault(battle, new String[0]))).append(" rounds=").append(roundsShown.getOrDefault(battle, List.of()).toString().replace(" ", ""));
		}
		return out.toString();
	}
}
