package gg.spaceclient.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Tells a server that this client is Space Client.
 *
 * One empty packet on join. It carries nothing because it does not need to:
 * the fact that it arrived at all is the entire message, and a payload with
 * fields would be a payload with a format to keep compatible.
 *
 * This is a courtesy, not a credential. Anyone can write a mod that sends this
 * packet, so a server using it is choosing who to let in, not proving anything.
 * The upside is that it answers the right question - a client that is running
 * right now is the only thing that can send it, which is exactly what a server
 * wanting current Space Client players needs to know.
 */
public final class Handshake {

    public static final Identifier CHANNEL =
            Identifier.fromNamespaceAndPath("spaceclient", "hello");

    /** An empty payload: arrival is the whole signal. */
    public record Hello() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Hello> TYPE =
                new CustomPacketPayload.Type<>(CHANNEL);

        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC =
                StreamCodec.unit(new Hello());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Called once from the client initialiser. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Hello.TYPE, Hello.CODEC);

        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
            try {
                // canSend is false on a server that never registered the
                // channel, which is most of them. Sending anyway would be
                // harmless but noisy in the log.
                if (ClientPlayNetworking.canSend(Hello.TYPE)) {
                    ClientPlayNetworking.send(new Hello());
                }
            } catch (Throwable ignored) {
                // A server that will not take the packet is a server that does
                // not want it. Never worth interrupting a join for.
            }
        });
    }

    private Handshake() {}
}
