package raidturns;

import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** What the party panel shows. Sent when something changes; the client counts the seconds down by itself. */
public record GroupStatePayload(int phase, int seconds, List<Member> members) implements CustomPacketPayload {
	public static final int NONE = 0, CHOOSING = 1, RESOLVING = 2;
	public static final Type<GroupStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("raidturns", "group_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GroupStatePayload> CODEC = StreamCodec.of(GroupStatePayload::write, GroupStatePayload::read);

	public record Member(UUID id, String name, RenderablePokemon pokemon, int status) {}

	private static void write(RegistryFriendlyByteBuf buf, GroupStatePayload state) {
		buf.writeVarInt(state.phase);
		buf.writeVarInt(state.seconds);
		buf.writeVarInt(state.members.size());
		for (Member member : state.members) {
			buf.writeUUID(member.id);
			buf.writeUtf(member.name);
			member.pokemon.saveToBuffer(buf);
			buf.writeVarInt(member.status);
		}
	}

	private static GroupStatePayload read(RegistryFriendlyByteBuf buf) {
		int phase = buf.readVarInt(), seconds = buf.readVarInt(), size = buf.readVarInt();
		List<Member> members = new ArrayList<>();
		for (int i = 0; i < size; i++)
			members.add(new Member(buf.readUUID(), buf.readUtf(), RenderablePokemon.Companion.loadFromBuffer(buf), buf.readVarInt()));
		return new GroupStatePayload(phase, seconds, members);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
