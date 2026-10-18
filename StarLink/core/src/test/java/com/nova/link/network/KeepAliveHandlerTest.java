package com.nova.link.network;

import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KeepAlive loop regression tests.
 *
 * <p>The production bug these lock down: the backend recognized the echo of its
 * own ping with {@code AtomicReference.compareAndSet(requestId, null)}. The
 * decoder rebuilds the requestId as {@code new UUID(mostSig, leastSig)}, so the
 * CAS compared references and never matched. Every echo was treated as a fresh
 * client ping and answered; the Java client answers unconditionally, so both
 * sides ping-ponged at the speed of the loopback network.</p>
 */
@DisplayName("KeepAlive loop guard")
class KeepAliveHandlerTest {

    private static final long PING_TS = 1_700_000_000_000L;

    /** A decoded packet: same value as the sent id, different instance. */
    private static KeepAlivePacket decodedEcho(UUID id, long timestamp) {
        KeepAlivePacket packet = new KeepAlivePacket(timestamp);
        packet.setRequestId(new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits()));
        return packet;
    }

    private static ClientConnection connection() {
        return new ClientConnection(new EmbeddedChannel());
    }

    @Nested
    @DisplayName("echo recognition")
    class EchoRecognition {

        @Test
        @DisplayName("a decoded echo of our own ping is not answered (no new KeepAlive is sent)")
        void decodedEchoDoesNotTriggerAnotherKeepAlive() {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();
            EmbeddedChannel channel = (EmbeddedChannel) connection.getChannel();

            KeepAlivePacket ping = handler.pingIdleConnection(connection, PING_TS);
            assertThat(ping).isNotNull();
            channel.writeOutbound(ping);
            channel.runPendingTasks();
            assertThat((Object) channel.readOutbound()).isNotNull();

            // The client echoes it back. The decoder rebuilds the UUID.
            KeepAlivePacket echo = decodedEcho(ping.getRequestId(), ping.getTimestamp());
            assertThat(echo.getRequestId()).isEqualTo(ping.getRequestId());
            handler.handle(connection, echo);
            channel.runPendingTasks();

            assertThat((Object) channel.readOutbound())
                    .as("an echo must never produce another KeepAlive")
                    .isNull();
            // The recognition above clears the pending slot; a further echo is
            // inert because its id is still in the "sent by us" memory.
            assertThat(connection.wasKeepAliveSentByUs(echo.getRequestId())).isTrue();
        }

        @Test
        @DisplayName("an echo whose requestId was rebuilt but timestamp preserved is still recognized")
        void timestampFallbackRecognizesEchoWithoutRequestId() {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();
            EmbeddedChannel channel = (EmbeddedChannel) connection.getChannel();

            KeepAlivePacket ping = handler.pingIdleConnection(connection, PING_TS);
            handler.handle(connection, decodedEcho(UUID.randomUUID(), ping.getTimestamp()));
            channel.runPendingTasks();

            assertThat((Object) channel.readOutbound())
                    .as("matching timestamp identifies our own ping")
                    .isNull();
        }

        @Test
        @DisplayName("an echo of an earlier reply is not answered either")
        void echoOfOurReplyIsNotAnswered() {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();
            EmbeddedChannel channel = (EmbeddedChannel) connection.getChannel();

            // Client-initiated ping: we must answer it exactly once.
            KeepAlivePacket clientPing = new KeepAlivePacket(PING_TS);
            handler.handle(connection, clientPing);
            channel.runPendingTasks();
            KeepAlivePacket reply = (KeepAlivePacket) channel.readOutbound();
            assertThat(reply).isNotNull();

            // The client echoes our reply; that is still our own packet.
            handler.handle(connection, decodedEcho(reply.getRequestId(), reply.getTimestamp()));
            channel.runPendingTasks();
            assertThat((Object) channel.readOutbound())
                    .as("echoing our reply must not start a conversation")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("normal keep-alive still works")
    class NormalKeepAlive {

        @Test
        @DisplayName("a client-initiated ping is answered with the same requestId and timestamp")
        void clientPingIsEchoedBack() {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();
            EmbeddedChannel channel = (EmbeddedChannel) connection.getChannel();

            KeepAlivePacket clientPing = new KeepAlivePacket(PING_TS);
            handler.handle(connection, clientPing);
            channel.runPendingTasks();

            Object outbound = channel.readOutbound();
            assertThat(outbound).isInstanceOf(KeepAlivePacket.class);
            KeepAlivePacket reply = (KeepAlivePacket) outbound;
            assertThat(reply.getRequestId()).isEqualTo(clientPing.getRequestId());
            assertThat(reply.getTimestamp()).isEqualTo(PING_TS);
        }

        @Test
        @DisplayName("write-idle still pings a genuinely idle connection")
        void idleConnectionStillGetsPinged() {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();
            EmbeddedChannel channel = (EmbeddedChannel) connection.getChannel();

            KeepAlivePacket ping = handler.pingIdleConnection(connection, PING_TS);
            assertThat(ping).isNotNull();
            channel.writeOutbound(ping);
            channel.runPendingTasks();
            assertThat((Object) channel.readOutbound()).isInstanceOf(KeepAlivePacket.class);
            assertThat(connection.hasPendingKeepAlive()).isTrue();
        }

        @Test
        @DisplayName("consecutive idle pings spaced by the idle interval keep flowing")
        void periodicPingsSurviveTheBudget() throws InterruptedException {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();

            // The budget is a real-time one-second window, so space the pings
            // past it instead of faking the clock the connection owns.
            for (int i = 0; i < 3; i++) {
                assertThat(handler.pingIdleConnection(connection, PING_TS + i * 30_000L))
                        .as("ping #%d must not be suppressed", i)
                        .isNotNull();
                Thread.sleep(1_100L);
            }
        }

        @Test
        @DisplayName("latency is recorded from the echoed timestamp")
        void latencyIsRecorded() {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();

            long sentAt = System.currentTimeMillis() - 250L;
            handler.handle(connection, new KeepAlivePacket(sentAt));

            assertThat(connection.getPing()).isGreaterThanOrEqualTo(250L);
            assertThat(connection.getLastPingAt()).isPositive();
        }
    }

    @Nested
    @DisplayName("flood guard")
    class FloodGuard {

        @Test
        @DisplayName("more pings than the per-second budget are dropped and warned")
        void excessPingsAreDropped() {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();
            EmbeddedChannel channel = (EmbeddedChannel) connection.getChannel();

            int max = connection.getKeepAliveBudget().getMaxPerWindow();
            int sent = 0;
            for (int i = 0; i < max * 20; i++) {
                if (handler.pingIdleConnection(connection, PING_TS + i) != null) {
                    sent++;
                }
            }

            assertThat(sent)
                    .as("the budget must cap a burst inside one second")
                    .isEqualTo(max);
            assertThat((Object) channel.readOutbound()).isNull();
        }

        @Test
        @DisplayName("a client ping flood is not amplified: excess replies are dropped")
        void excessClientPingsAreNotAnswered() {
            KeepAliveHandler handler = new KeepAliveHandler();
            ClientConnection connection = connection();
            EmbeddedChannel channel = (EmbeddedChannel) connection.getChannel();


            int max = connection.getKeepAliveBudget().getMaxPerWindow();
            for (int i = 0; i < max * 20; i++) {
                handler.handle(connection, new KeepAlivePacket(PING_TS + i));
            }
            channel.runPendingTasks();

            int replies = 0;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                assertThat(outbound).isInstanceOf(KeepAlivePacket.class);
                replies++;
            }
            assertThat(replies)
                    .as("a flood must not turn into a reply flood")
                    .isEqualTo(max);
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
}
