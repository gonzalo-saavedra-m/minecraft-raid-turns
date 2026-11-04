package raidturns;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The party panel says this player still has to choose, but their client has no move menu open. */
public record RequestMenuPayload() implements CustomPacketPayload {
	public static final Type<RequestMenuPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("raidturns", "request_menu"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RequestMenuPayload> CODEC = StreamCodec.unit(new RequestMenuPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
