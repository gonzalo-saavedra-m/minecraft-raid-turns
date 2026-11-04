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
	// Below Cobblemon's own battle tile (BattleOverlay: y=10, 40 tall)
	private static final int PANEL_X = 12, PANEL_Y = 56, MAX_ROW = 22, MIN_ROW = 12, RESERVED_BOTTOM = 70, PORTRAIT = 18;
	private static final int[] STATUS_COLORS = {0xFFFFC857, 0xFF5BD75B, 0xFF7FC8FF, 0xFF8A8A8A};
	private static final String[] STATUS_ICONS = {"⌛", "✔", "⚔", "☠"};

	private static GroupStatePayload state;
	private static long deadlineMillis;
	private static UUID lastSelected;
	private static final Map<UUID, FloatingState> PORTRAITS = new HashMap<>();

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(GroupStatePayload.TYPE, (payload, context) -> {
			state = payload.phase() == GroupStatePayload.NONE ? null : payload;
			deadlineMillis = System.currentTimeMillis() + payload.seconds() * 1000L;
		});
		ClientTickEvents.END_CLIENT_TICK.register(RaidTurnsClient::sendSelection);
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
		var others = state.members().stream().filter(member -> !member.id().equals(client.player.getUUID())).toList();
		if (others.isEmpty()) return;
		int row = Math.max(MIN_ROW, Math.min(MAX_ROW, (graphics.guiHeight() - y - RESERVED_BOTTOM) / others.size()));
		for (var member : others) {
			graphics.fill(PANEL_X, y, PANEL_X + 110, y + row - 2, 0x90000000);
			int status = Math.min(member.status(), STATUS_ICONS.length - 1);
			drawPortrait(graphics, member, y, row, partialTicks);
			int textY = y + (row - 2 - font.lineHeight) / 2 + 1;
			int nameColor = status == RaidRound.Status.FAINTED.ordinal() ? 0xFF8A8A8A : 0xFFFFFFFF;
			graphics.drawString(font, font.plainSubstrByWidth(member.name(), 72), PANEL_X + PORTRAIT + 4, textY, nameColor, false);
			graphics.drawString(font, STATUS_ICONS[status], PANEL_X + 98, textY, STATUS_COLORS[status], false);
			y += row;
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
