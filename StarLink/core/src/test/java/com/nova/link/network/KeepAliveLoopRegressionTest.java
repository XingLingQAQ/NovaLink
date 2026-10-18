package com.nova.link.network;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.codec.PacketDecoder;
import com.nova.chat.common.protocol.codec.PacketEncoder;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression for the KeepAlive infinite loop, driven through the
 * real wire codec.
 *
 * <p>Why this test and not just a unit assertion: the bug only appears after a
 * packet has crossed the codec. {@code Packet.decode} rebuilds the requestId as
 * {@code new UUID(mostSig, leastSig)}, so the id that comes back is equal by
 * value but not by reference. The old guard compared references, never matched,
 * and answered every echo — the peer answers unconditionally, so the loop never
 * terminated.</p>
 *
 * <p>Both sides are modelled faithfully: the server path is the production
 * {@link KeepAliveHandler}; the peer performs the same unconditional echo as
 * {@code CoreNetworkClient.handleKeepAlive} (client-core, read-only here).</p>
 */
@DisplayName("KeepAlive wire-level loop regression")
class KeepAliveLoopRegressionTest {

    private static final int MAX_ROUND_TRIPS = 200;

    private final PacketRegistry registry = NovaProtocol.createRegistry();

    /** Encodes and immediately decodes a packet, exactly as the wire does. */
    private Packet roundTrip(Packet packet) {
        EmbeddedChannel codec = new EmbeddedChannel(
                new PacketEncoder(registry), new PacketDecoder(registry));
        try {
            codec.writeOutbound(packet);
            ByteBuf outbound = codec.readOutbound();
            byte[] raw = new byte[outbound.readableBytes()];
            outbound.readBytes(raw);
            outbound.release();

            codec.writeInbound(Unpooled.wrappedBuffer(raw));
            return codec.readInbound();
        } finally {
            codec.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("server ping -> peer echo terminates instead of ping-ponging")
    void echoChainTerminates() {
        KeepAliveHandler handler = new KeepAliveHandler();
        ClientConnection connection = new ClientConnection(new EmbeddedChannel());
        EmbeddedChannel serverChannel = (EmbeddedChannel) connection.getChannel();

        // Round 1: write-idle makes the server ping.
        KeepAlivePacket ping = handler.pingIdleConnection(connection, System.currentTimeMillis());
        assertThat(ping).as("an idle connection must still be pinged").isNotNull();

        Packet toPeer = roundTrip(ping);
        int serverReplies = 0;

        for (int i = 0; i < MAX_ROUND_TRIPS; i++) {
            assertThat(toPeer)
                    .as("the exchange must terminate; round %d still had a packet", i)
                    .isInstanceOf(KeepAlivePacket.class);

            // Peer: unconditional echo, as client-core does today.
            KeepAlivePacket incoming = (KeepAlivePacket) toPeer;
            KeepAlivePacket peerReply = new KeepAlivePacket(incoming.getTimestamp());
            peerReply.setRequestId(incoming.getRequestId());


            // Server: handle the decoded echo, then look for another send.
            handler.handle(connection, (KeepAlivePacket) roundTrip(peerReply));
            serverChannel.runPendingTasks();

            Object next = serverChannel.readOutbound();
            if (next == null) {
                assertThat(serverReplies)
                        .as("the echo must be recognized without a single reply")
                        .isZero();
                return;
            }
            serverReplies++;
            toPeer = roundTrip((Packet) next);
        }

        throw new AssertionError("KeepAlive loop did not terminate after "
                + MAX_ROUND_TRIPS + " round trips (" + serverReplies + " server replies)");
    }

    @Test
    @DisplayName("the decoded requestId is equal by value but not by reference")
    void decoderRebuildsTheRequestIdInstance() {
        KeepAlivePacket original = new KeepAlivePacket(System.currentTimeMillis());
        KeepAlivePacket decoded = (KeepAlivePacket) roundTrip(original);

        assertThat(decoded.getRequestId()).isEqualTo(original.getRequestId());
        assertThat(decoded.getRequestId()).isNotSameAs(original.getRequestId());
        assertThat(decoded.getTimestamp()).isEqualTo(original.getTimestamp());
    }

    @Test
    @DisplayName("a genuine client ping still gets exactly one reply")
    void clientPingStillGetsOneReply() {
        KeepAliveHandler handler = new KeepAliveHandler();
        ClientConnection connection = new ClientConnection(new EmbeddedChannel());
        EmbeddedChannel serverChannel = (EmbeddedChannel) connection.getChannel();

        KeepAlivePacket clientPing = new KeepAlivePacket(System.currentTimeMillis());
        handler.handle(connection, (KeepAlivePacket) roundTrip(clientPing));
        serverChannel.runPendingTasks();

        assertThat((Object) serverChannel.readOutbound()).isInstanceOf(KeepAlivePacket.class);
        assertThat((Object) serverChannel.readOutbound())
                .as("one ping, one reply")
                .isNull();
    }
}
