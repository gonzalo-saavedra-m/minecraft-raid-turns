package raidturns;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.gui.PokemonGuiUtilsKt;
import com.cobblemon.mod.common.client.gui.ProfileTransformType;
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RaidTurnsClient implements ClientModInitializer {
	// Takes the place of Cobblemon's own battle tile, hidden during a raid by BattleOverlayMixin
	private static final int PANEL_X = 12, PANEL_Y = 10, PANEL_WIDTH = 156, MAX_ROW = 24, MIN_ROW = 21, RESERVED_BOTTOM = 30, PORTRAIT = 18, BAR_WIDTH = 34;
	private static final int MENU_RETRY_TICKS = 40;
	private static final int[] STATUS_COLORS = {0xFFFFC857, 0xFF5BD75B, 0xFF7FC8FF, 0xFF8A8A8A};
	private static final String[] STATUS_ICONS = {"⌛", "✔", "⚔", "☠"};
	private static final Map<String, Integer> AILMENT_COLORS = Map.of("brn", 0xFFFF9248, "par", 0xFFFFE066, "slp", 0xFFB0B0C8, "frz", 0xFF8CE0FF, "psn", 0xFFC77DFF, "tox", 0xFFC77DFF);

	private static GroupStatePayload state;
	private static long deadlineMillis;
	private static int menuMissingTicks;
	private static UUID lastSelected;

	/** Mixins hide Cobblemon's left side of the HUD while this is true. */
	public static boolean panelShown() {
		return state != null && !state.members().isEmpty();
	}
	private static final Map<UUID, FloatingState> PORTRAITS = new HashMap<>();

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(GroupStatePayload.TYPE, (payload, context) -> {
			state = payload.phase() == GroupStatePayload.NONE ? null : payload;
			deadlineMillis = System.currentTimeMillis() + payload.seconds() * 1000L;
		});
		ClientTickEvents.END_CLIENT_TICK.register(RaidTurnsClient::sendSelection);
		ClientTickEvents.END_CLIENT_TICK.register(RaidTurnsClient::askForMissingMenu);
		HudRenderCallback.EVENT.register((graphics, tick) -> render(graphics, tick.getGameTimeDeltaPartialTick(true)));
	}

	private static void sendSelection(Minecraft client) {
		if (client.player == null || client.getConnection() == null || !ClientPlayNetworking.canSend(SelectedPokemonPayload.TYPE)) {
			lastSelected = null;
			return;
		}
		var storage = CobblemonClient.INSTANCE.getStorage();
		Pokemon selected = storage.getSelectedSlot() < 0 ? null : storage.getParty().get(storage.getSelectedSlot());
		UUID id = selected == null ? null : selected.getUuid();
		if (id != null && !id.equals(lastSelected)) ClientPlayNetworking.send(new SelectedPokemonPayload(id));
		lastSelected = id;
	}

	/** Missing according to the server, with no menu here: without this the player would wait for the timeout. */
	private static void askForMissingMenu(Minecraft client) {
		var battle = CobblemonClient.INSTANCE.getBattle();
		boolean missing = client.player != null && battle != null && state != null && state.phase() == GroupStatePayload.CHOOSING && !battle.getMustChoose()
				&& state.members().stream().anyMatch(member -> member.id().equals(client.player.getUUID()) && member.status() == RaidRound.Status.WAITING.ordinal());
		menuMissingTicks = missing ? menuMissingTicks + 1 : 0;
		if (menuMissingTicks >= MENU_RETRY_TICKS && ClientPlayNetworking.canSend(RequestMenuPayload.TYPE)) {
			ClientPlayNetworking.send(new RequestMenuPayload());
			menuMissingTicks = 0;
		}
	}

	private static void render(GuiGraphics graphics, float partialTicks) {
		Minecraft client = Minecraft.getInstance();
		if (state == null || client.player == null || client.options.hideGui) return;
		Font font = client.font;
		int y = PANEL_Y;
		if (state.phase() == GroupStatePayload.CHOOSING) {
			long left = Math.max(0, (deadlineMillis - System.currentTimeMillis() + 999) / 1000);
			graphics.drawString(font, Component.translatable("raidturns.seconds", left), PANEL_X, y, left <= 10 ? 0xFFFF6B6B : 0xFFFFFFFF, true);
			y += font.lineHeight + 3;
		}
		var members = state.members();
		if (members.isEmpty()) return;
		int row = Math.max(MIN_ROW, Math.min(MAX_ROW, (graphics.guiHeight() - y - RESERVED_BOTTOM) / members.size()));
		for (var member : members) {
			graphics.fill(PANEL_X, y, PANEL_X + PANEL_WIDTH, y + row - 2, 0x90000000);
			boolean boss = member.status() == RaidRound.Status.BOSS.ordinal();
			boolean fainted = member.status() == RaidRound.Status.FAINTED.ordinal();
			drawPortrait(graphics, member, y, row, partialTicks);
			int textX = PANEL_X + PORTRAIT + 4;
			String name = boss ? member.pokemon().getSpecies().getTranslatedName().getString() : member.name();
			graphics.drawString(font, font.plainSubstrByWidth(name, PANEL_WIDTH - PORTRAIT - 18), textX, y + 1, fainted ? 0xFF8A8A8A : boss ? 0xFFFFC857 : 0xFFFFFFFF, false);
			if (!boss) graphics.drawString(font, STATUS_ICONS[Math.min(member.status(), STATUS_ICONS.length - 1)], PANEL_X + PANEL_WIDTH - 11, y + 1, STATUS_COLORS[Math.min(member.status(), STATUS_COLORS.length - 1)], false);
			drawDetails(graphics, font, member, textX, y + font.lineHeight + 2);
			y += row;
		}
	}

	/** Health bar, status and stat changes of one row. */
	private static void drawDetails(GuiGraphics graphics, Font font, GroupStatePayload.Member member, int x, int y) {
		int filled = Math.round(BAR_WIDTH * Math.max(0, Math.min(1, member.health())));
		graphics.fill(x, y + 2, x + BAR_WIDTH, y + 6, 0xFF3A3A3A);
		graphics.fill(x, y + 2, x + filled, y + 6, member.health() > 0.5f ? 0xFF5BD75B : member.health() > 0.2f ? 0xFFFFC857 : 0xFFFF6B6B);
		x += BAR_WIDTH + 3;
		int limit = PANEL_X + PANEL_WIDTH - 2;
		if (!member.ailment().equals("-")) {
			Component ailment = Component.translatable("raidturns.status." + member.ailment());
			graphics.drawString(font, ailment, x, y, AILMENT_COLORS.getOrDefault(member.ailment(), 0xFFFFFFFF), false);
			x += font.width(ailment) + 3;
		}
		if (member.boosts().equals("-")) return;
		for (String boost : member.boosts().split(",")) {
			String[] parts = boost.split(":");
			int stages = Integer.parseInt(parts[1]);
			Component text = Component.literal(String.format("%+d ", stages)).append(Component.translatable("raidturns.stat." + parts[0]));
			if (x + font.width(text) > limit) return;
			graphics.drawString(font, text, x, y, stages > 0 ? 0xFF7CFC8A : 0xFFFF6B6B, false);
			x += font.width(text) + 3;
		}
	}

	private static void drawPortrait(GuiGraphics graphics, GroupStatePayload.Member member, int y, int row, float partialTicks) {
		int size = Math.min(PORTRAIT, row - 2);
		PoseStack pose = graphics.pose();
		pose.pushPose();
		pose.translate(PANEL_X + 2 + size / 2.0, y + size / 4.0, 0);
		graphics.enableScissor(PANEL_X, y, PANEL_X + size + 3, y + row - 2);
		PokemonGuiUtilsKt.drawProfilePokemon(member.pokemon(), pose, new Quaternionf().rotationXYZ(0.2f, (float) Math.toRadians(-30), 0),
				PoseType.PORTRAIT, PORTRAITS.computeIfAbsent(member.id(), id -> new FloatingState()), partialTicks, size * 0.7f,
				ProfileTransformType.PROFILE, true, 1f, 1f, 1f, 1f, 0f, 0f, LightTexture.FULL_BRIGHT);
		graphics.disableScissor();
		pose.popPose();
	}
}
