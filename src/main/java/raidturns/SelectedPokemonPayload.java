package raidturns;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** The Pokémon selected in the client's party bar: the server has no other way of knowing it. */
public record SelectedPokemonPayload(UUID pokemon) implements CustomPacketPayload {
	public static final Type<SelectedPokemonPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("raidturns", "selected_pokemon"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SelectedPokemonPayload> CODEC =
			StreamCodec.composite(UUIDUtil.STREAM_CODEC, SelectedPokemonPayload::pokemon, SelectedPokemonPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
