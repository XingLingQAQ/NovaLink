package com.nova.chat.client.network;

import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proxy-side KeepAlive direction tests.
 *
 * <p>The client used to answer every inbound KeepAlive unconditionally. With
 * the backend doing the same, one packet became a loop at the speed of the
 * link. NovaProtocol has no direction bit, so the client must remember the
 * requestIds it wrote: an inbound packet carrying one of them is our own echo
 * (record latency, answer nothing); anything else is a peer ping (answer once,
 * rate-limited).</p>
 */
@DisplayName("CoreNetworkClient KeepAlive direction")
class CoreNetworkClientKeepAliveTest {

    private static final long PING_TS = 1_700_000_000_000L;

    private static final class RecordingLogger implements ClientLogger {
        final List<String> warns = new ArrayList<>();

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warns.add(message);
        }

        @Override
        public void debug(String message) {
        }

        @Override
        public void error(String message) {
        }
    }

    private static final class NoOpScheduler implements SchedulerBridge {
        @Override
        public void runAsync(Runnable task) {
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
        }
    }

    private static ClientConnectionConfig basicConfig() {
        return ClientConnectionConfig.builder()
                .host("127.0.0.1")
                .port(8888)
                .username("proxy")
                .password("s3cret")
                .build();
    }

    /** A decoded packet: equal UUID value, different instance (as Packet.decode() builds it). */
    private static KeepAlivePacket decoded(UUID id, long timestamp) {
        KeepAlivePacket packet = new KeepAlivePacket(timestamp);
        packet.setRequestId(new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits()));
        return packet;
    }

    private static List<KeepAlivePacket> drain(EmbeddedChannel channel) {
        List<KeepAlivePacket> out = new ArrayList<>();
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            assertThat(packet).isInstanceOf(KeepAlivePacket.class);
            out.add((KeepAlivePacket) packet);
        }
        return out;
    }

    /** Client with a live EmbeddedChannel as its current generation. */
    private static CoreNetworkClient connectedClient(EmbeddedChannel channel, RecordingLogger logger) {
        CoreNetworkClient client = new CoreNetworkClient(
                basicConfig(), PlatformType.VELOCITY, new NoOpScheduler(), logger
        );
        client.setChannelForTest(channel);
        return client;
    }

    private static CoreNetworkClient connectedClient(RecordingLogger logger) {
        return connectedClient(new EmbeddedChannel(), logger);
    }

    @Nested
    @DisplayName("echo of our own KeepAlive")
    class OurEcho {

        @Test
        @DisplayName("an echo of a client-written ping is not answered")
        void echoOfClientPingIsNotAnswered() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);

            KeepAlivePacket ping = new KeepAlivePacket(PING_TS);
            client.sendPacket(ping);
            channel.runPendingTasks();
            assertThat(drain(channel)).hasSize(1);

            // The peer echoes it; the decoder rebuilds the UUID instance.
            client.handlePacket(decoded(ping.getRequestId(), PING_TS));
            channel.runPendingTasks();

            assertThat(drain(channel))
                    .as("an echo of our own ping must never produce another KeepAlive")
                    .isEmpty();
        }

        @Test
        @DisplayName("an echo of our own reply is not answered either")
        void echoOfOurReplyIsNotAnswered() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);

            // Peer-initiated ping: answered exactly once.
            KeepAlivePacket peerPing = new KeepAlivePacket(PING_TS);
            client.handlePacket(peerPing);
            channel.runPendingTasks();
            List<KeepAlivePacket> replies = drain(channel);
            assertThat(replies).hasSize(1);

            // The peer echoes our reply; that is still our own packet.
            client.handlePacket(decoded(replies.get(0).getRequestId(), PING_TS));
            channel.runPendingTasks();

            assertThat(drain(channel))
                    .as("echoing our reply must not start a conversation")
                    .isEmpty();
        }

        @Test
        @DisplayName("repeated echoes never amplify")
        void repeatedEchoesDoNotAmplify() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);

            KeepAlivePacket ping = new KeepAlivePacket(PING_TS);
            client.sendPacket(ping);
            channel.runPendingTasks();
            assertThat(drain(channel)).hasSize(1);

            // A peer that echoes on every receipt: feed the same echo 50 times.
            for (int i = 0; i < 50; i++) {
                client.handlePacket(decoded(ping.getRequestId(), PING_TS));
            }
            channel.runPendingTasks();

            assertThat(drain(channel))
                    .as("50 echoes of one ping must produce zero replies")
                    .isEmpty();
        }

        @Test
        @DisplayName("latency is read from the echoed timestamp")
        void latencyComesFromEchoedTimestamp() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);
            assertThat(client.getLastKeepAliveLatencyMs()).isEqualTo(-1L);

            long sentAt = System.currentTimeMillis() - 250L;
            KeepAlivePacket ping = new KeepAlivePacket(sentAt);
            client.sendPacket(ping);
            channel.runPendingTasks();
            drain(channel);

            client.handlePacket(decoded(ping.getRequestId(), sentAt));

            assertThat(client.getLastKeepAliveLatencyMs()).isGreaterThanOrEqualTo(250L);
            assertThat(client.getLastKeepAliveLatencyAt()).isPositive();
        }
    }

    @Nested
    @DisplayName("peer-initiated KeepAlive")
    class PeerPing {

        @Test
        @DisplayName("is answered once with the original requestId and timestamp")
        void peerPingIsAnsweredOnce() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);

            KeepAlivePacket peerPing = new KeepAlivePacket(PING_TS);
            client.handlePacket(peerPing);
            channel.runPendingTasks();

            List<KeepAlivePacket> replies = drain(channel);
            assertThat(replies).hasSize(1);
            assertThat(replies.get(0).getRequestId()).isEqualTo(peerPing.getRequestId());
            assertThat(replies.get(0).getTimestamp()).isEqualTo(PING_TS);

            // A duplicate delivery of the same peer ping would now match the
            // remembered reply id and be treated as an echo: still no growth.
            client.handlePacket(decoded(peerPing.getRequestId(), PING_TS));
            channel.runPendingTasks();
            assertThat(drain(channel)).isEmpty();
        }

        @Test
        @DisplayName("distinct peer pings are answered individually")
        void distinctPeerPingsAreAnswered() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);

            client.handlePacket(new KeepAlivePacket(PING_TS));
            client.handlePacket(new KeepAlivePacket(PING_TS + 1));
            channel.runPendingTasks();

            assertThat(drain(channel)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("flood guard")
    class FloodGuard {

        @Test
        @DisplayName("a peer ping flood is capped at the per-second budget")
        void peerPingFloodIsCapped() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);

            int max = KeepAliveBudget.DEFAULT_MAX_PER_WINDOW;
            for (int i = 0; i < max * 20; i++) {
                // Fresh requestIds so none of them count as an echo.
                client.handlePacket(new KeepAlivePacket(PING_TS + i));
            }
            channel.runPendingTasks();

            assertThat(drain(channel))
                    .as("a flood must not turn into a reply flood")
                    .hasSize(max);
            assertThat(logger.warns)
                    .as("the drop is warned")
                    .anyMatch(m -> m.contains("flood guard"));
        }

        @Test
        @DisplayName("warn is throttled instead of firing once per dropped packet")
        void repeatedDropsWarnOnce() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);

            for (int i = 0; i < 100; i++) {
                client.handlePacket(new KeepAlivePacket(PING_TS + i));
            }

            assertThat(logger.warns)
                    .as("an unthrottled warn would itself be a flood")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a platform-initiated ping burst is capped too")
        void directSendIsCapped() {
            RecordingLogger logger = new RecordingLogger();
            EmbeddedChannel channel = new EmbeddedChannel();
            CoreNetworkClient client = connectedClient(channel, logger);

            int max = KeepAliveBudget.DEFAULT_MAX_PER_WINDOW;
            for (int i = 0; i < max * 20; i++) {
                client.sendPacket(new KeepAlivePacket(PING_TS + i));
            }
            channel.runPendingTasks();

            assertThat(drain(channel))
                    .as("the budget applies at the single write choke point")
                    .hasSize(max);
        }
    }

    @Nested
    @DisplayName("budget unit")
    class BudgetUnit {

        @Test
        @DisplayName("window slides: a slot frees up after the window elapses")
        void windowSlides() {
            long[] now = {0L};
            KeepAliveBudget budget = new KeepAliveBudget(2, 1_000_000_000L, () -> now[0]);

            assertThat(budget.tryAcquire()).isTrue();
            assertThat(budget.tryAcquire()).isTrue();
            assertThat(budget.tryAcquire()).isFalse();

            now[0] = 1_000_000_001L;
            assertThat(budget.tryAcquire()).isTrue();
        }
    }

    @Nested
    @DisplayName("recent-id memory unit")
    class RecentIdsUnit {

        @Test
        @DisplayName("matches by UUID value, not reference")
        void matchesByValue() {
            RecentKeepAlives recent = new RecentKeepAlives(4, 60_000_000_000L);
            UUID id = UUID.randomUUID();
            recent.remember(id);

            UUID rebuilt = new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits());
            assertThat(rebuilt).isNotSameAs(id);
            assertThat(recent.contains(rebuilt)).isTrue();
        }

        @Test
        @DisplayName("expired ids are not recognized")
        void expiredIdsDoNotMatch() {
            long[] now = {0L};
            RecentKeepAlives recent = new RecentKeepAlives(4, 1_000L, () -> now[0]);
            UUID id = UUID.randomUUID();
            recent.remember(id);

            now[0] = 1_000L;
            assertThat(recent.contains(id)).isFalse();
        }

        @Test
        @DisplayName("capacity evicts the oldest id")
        void capacityEvictsOldest() {
            RecentKeepAlives recent = new RecentKeepAlives(2, 60_000_000_000L);
            UUID first = UUID.randomUUID();
            recent.remember(first);
            recent.remember(UUID.randomUUID());
            recent.remember(UUID.randomUUID());

            assertThat(recent.contains(first)).isFalse();
        }

        @Test
        @DisplayName("clear forgets every id on a new connection generation")
        void clearForgetsEverything() {
            RecentKeepAlives recent = new RecentKeepAlives(4, 60_000_000_000L);
            UUID id = UUID.randomUUID();
            recent.remember(id);
            recent.clear();

            assertThat(recent.contains(id)).isFalse();
        }
    }
}
