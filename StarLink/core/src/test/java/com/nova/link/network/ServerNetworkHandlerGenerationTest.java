package com.nova.link.network;

import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ServerNetworkHandler active generations")
class ServerNetworkHandlerGenerationTest {

    @Test
    @DisplayName("new connection atomically takes over the same clientId")
    void newerConnectionTakesOver() {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, false);
        ClientConnection oldConnection = connection(handler);
        ClientConnection newConnection = connection(handler);

        try {
            assertThat(handler.activateAuthenticated(oldConnection, "survival")).isTrue();
            long oldGeneration = oldConnection.getGeneration();

            assertThat(handler.activateAuthenticated(newConnection, "survival")).isTrue();

            assertThat(handler.findByClientId("survival")).isSameAs(newConnection);
            assertThat(handler.isActiveGeneration(newConnection)).isTrue();
            assertThat(handler.isActiveGeneration(oldConnection)).isFalse();
            assertThat(newConnection.getGeneration()).isGreaterThan(oldGeneration);
            assertThat(oldConnection.isAuthenticated()).isFalse();
            assertThat(oldConnection.isActive()).isFalse();
        } finally {
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("late old disconnect cannot clear new registration, players, or subscriptions")
    void oldDisconnectDoesNotClearNewGenerationState() {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, false);
        ClientConnection oldConnection = connection(handler);
        ClientConnection newConnection = connection(handler);
        Set<String> permissions = ConcurrentHashMap.newKeySet();
        Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
        Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        AtomicInteger activeCleanupCalls = new AtomicInteger();

        handler.setDisconnectListener((connection, activeGenerationEnded) -> {
            if (activeGenerationEnded) {
                activeCleanupCalls.incrementAndGet();
                permissions.remove(connection.getClientId());
                onlinePlayers.clear();
                subscriptions.clear();
            }
        });

        try {
            assertThat(handler.activateAuthenticated(oldConnection, "survival", () -> {
                permissions.add("survival");
                onlinePlayers.add("player-1");
                subscriptions.add("global");
            })).isTrue();
            assertThat(handler.activateAuthenticated(newConnection, "survival", () ->
                    permissions.add("survival"))).isTrue();

            handler.onClientDisconnected(oldConnection);

            assertThat(handler.findByClientId("survival")).isSameAs(newConnection);
            assertThat(permissions).containsExactly("survival");
            assertThat(onlinePlayers).containsExactly("player-1");
            assertThat(subscriptions).containsExactly("global");
            assertThat(activeCleanupCalls).hasValue(0);

            handler.onClientDisconnected(newConnection);
            assertThat(activeCleanupCalls).hasValue(1);
            assertThat(permissions).isEmpty();
            assertThat(onlinePlayers).isEmpty();
            assertThat(subscriptions).isEmpty();
        } finally {
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("stale messages and fan-out are rejected while other clientIds remain independent")
    void staleMessagesRejectedWithoutAffectingOtherClients() {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, false);
        ClientConnection oldConnection = connection(handler);
        ClientConnection newConnection = connection(handler);
        ClientConnection otherConnection = connection(handler);
        Set<String> handledConnections = ConcurrentHashMap.newKeySet();
        handler.registerHandler(KeepAlivePacket.class,
                (connection, packet) -> handledConnections.add(connection.getConnectionId()));

        try {
            handler.activateAuthenticated(oldConnection, "survival");
            handler.activateAuthenticated(otherConnection, "creative");
            handler.activateAuthenticated(newConnection, "survival");

            handler.handlePacket(oldConnection, new KeepAlivePacket(1L));
            handler.handlePacket(newConnection, new KeepAlivePacket(2L));
            handler.handlePacket(otherConnection, new KeepAlivePacket(3L));

            assertThat(handledConnections)
                    .containsExactlyInAnyOrder(newConnection.getConnectionId(), otherConnection.getConnectionId());

            handler.broadcastAuthenticated(new KeepAlivePacket(4L));
            assertThat((Object) ((EmbeddedChannel) newConnection.getChannel()).readOutbound())
                    .isInstanceOf(KeepAlivePacket.class);
            assertThat((Object) ((EmbeddedChannel) otherConnection.getChannel()).readOutbound())
                    .isInstanceOf(KeepAlivePacket.class);
            assertThat((Object) ((EmbeddedChannel) oldConnection.getChannel()).readOutbound()).isNull();
            assertThat(handler.findByClientId("creative")).isSameAs(otherConnection);
        } finally {
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("concurrent authentication leaves exactly one active generation")
    void concurrentTakeoverHasSingleWinner() throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, false);
        int contenders = 16;
        List<ClientConnection> connections = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            connections.add(connection(handler));
        }
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contenders);
        List<Thread> threads = new ArrayList<>();

        try {
            for (ClientConnection connection : connections) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        handler.activateAuthenticated(connection, "survival");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                threads.add(thread);
                thread.start();
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            for (Thread thread : threads) {
                thread.join(1_000L);
            }

            ClientConnection winner = handler.findByClientId("survival");
            assertThat(winner).isNotNull();
            assertThat(connections.stream().filter(handler::isActiveGeneration).count()).isEqualTo(1L);
            assertThat(connections.stream().map(ClientConnection::getGeneration).distinct().count())
                    .isEqualTo(contenders);
        } finally {
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("a physical connection dispatches at most one concurrent handshake")
    void concurrentHandshakesOnOneConnectionAreClaimedOnce() throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, false);
        ClientConnection connection = connection(handler);
        int contenders = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch releaseAuthentication = new CountDownLatch(1);
        CountDownLatch rejected = new CountDownLatch(contenders - 1);
        AtomicInteger authenticationCalls = new AtomicInteger();
        Set<Thread> authenticationThreads = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();

        handler.registerHandler(HandshakePacket.class, (current, packet) -> {
            authenticationThreads.add(Thread.currentThread());
            authenticationCalls.incrementAndGet();
            await(releaseAuthentication);
        });

        try {
            for (int i = 0; i < contenders; i++) {
                Thread thread = new Thread(() -> {
                    await(start);
                    handler.handlePacket(connection, new HandshakePacket());
                    if (!authenticationThreads.contains(Thread.currentThread())) {
                        rejected.countDown();
                    }
                });
                threads.add(thread);
                thread.start();
            }

            start.countDown();
            assertThat(rejected.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(authenticationCalls).hasValue(1);

            releaseAuthentication.countDown();
            for (Thread thread : threads) {
                thread.join(1_000L);
                assertThat(thread.isAlive()).isFalse();
            }
        } finally {
            releaseAuthentication.countDown();
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("queued old-generation work is rejected after takeover")
    void queuedOldGenerationWorkIsRejectedAfterTakeover() throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, true);
        ClientConnection blocker = connection(handler);
        ClientConnection oldConnection = connection(handler);
        ClientConnection newConnection = connection(handler);
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch queueDrained = new CountDownLatch(1);
        AtomicInteger staleCalls = new AtomicInteger();

        handler.registerHandler(KeepAlivePacket.class, (connection, packet) -> {
            if (connection == blocker) {
                blockerEntered.countDown();
                await(releaseBlocker);
            } else if (connection == oldConnection) {
                staleCalls.incrementAndGet();
            } else if (connection == newConnection) {
                queueDrained.countDown();
            }
        });

        try {
            handler.activateAuthenticated(blocker, "creative");
            handler.activateAuthenticated(oldConnection, "survival");
            handler.handlePacket(blocker, new KeepAlivePacket(1L));
            assertThat(blockerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            handler.handlePacket(oldConnection, new KeepAlivePacket(2L));
            assertThat(handler.activateAuthenticated(newConnection, "survival")).isTrue();
            handler.handlePacket(newConnection, new KeepAlivePacket(3L));

            releaseBlocker.countDown();
            assertThat(queueDrained.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(staleCalls).hasValue(0);
        } finally {
            releaseBlocker.countDown();
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("new generation stays hidden until bootstrap and index publication complete")
    void preparedGenerationIsNotExposedBeforePublication() throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, false);
        ClientConnection oldConnection = connection(handler);
        ClientConnection newConnection = connection(handler);
        CountDownLatch bootstrapEntered = new CountDownLatch(1);
        CountDownLatch releaseBootstrap = new CountDownLatch(1);
        CountDownLatch takeoverDone = new CountDownLatch(1);

        try {
            assertThat(handler.activateAuthenticated(oldConnection, "survival")).isTrue();
            Thread takeoverThread = new Thread(() -> {
                handler.activateAuthenticated(newConnection, "survival", () -> {
                    bootstrapEntered.countDown();
                    await(releaseBootstrap);
                });
                takeoverDone.countDown();
            });
            takeoverThread.start();

            assertThat(bootstrapEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(newConnection.isAuthenticated()).isFalse();
            assertThat(newConnection.sendPacket(new KeepAlivePacket(1L))).isCompletedExceptionally();
            assertThat(takeoverDone.await(100, TimeUnit.MILLISECONDS)).isFalse();

            releaseBootstrap.countDown();
            assertThat(takeoverDone.await(5, TimeUnit.SECONDS)).isTrue();
            takeoverThread.join(1_000L);
            assertThat(handler.findByClientId("survival")).isSameAs(newConnection);
            assertThat(handler.isActiveGeneration(newConnection)).isTrue();
        } finally {
            releaseBootstrap.countDown();
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("a superseded prepared generation cannot reactivate")
    void supersededPreparedGenerationCannotReactivate() {
        ClientConnection connection = new ClientConnection(new EmbeddedChannel());

        connection.prepareGeneration("survival", 1L);
        connection.supersede();

        assertThat(connection.activatePreparedGeneration(() -> true)).isFalse();
        assertThat(connection.isAuthenticated()).isFalse();
        assertThat(connection.sendPacket(new KeepAlivePacket(1L))).isCompletedExceptionally();
        connection.close();
    }

    @Test
    @DisplayName("takeover waits for in-flight old-generation business work")
    void takeoverFencesInFlightBusinessWork() throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, false);
        ClientConnection oldConnection = connection(handler);
        ClientConnection newConnection = connection(handler);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch takeoverDone = new CountDownLatch(1);
        handler.registerHandler(KeepAlivePacket.class, (connection, packet) -> {
            handlerEntered.countDown();
            try {
                releaseHandler.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            handler.activateAuthenticated(oldConnection, "survival");
            Thread businessThread = new Thread(() ->
                    handler.handlePacket(oldConnection, new KeepAlivePacket(1L)));
            businessThread.start();
            assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Thread takeoverThread = new Thread(() -> {
                handler.activateAuthenticated(newConnection, "survival");
                takeoverDone.countDown();
            });
            takeoverThread.start();

            assertThat(takeoverDone.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(handler.findByClientId("survival")).isSameAs(oldConnection);

            releaseHandler.countDown();
            assertThat(takeoverDone.await(5, TimeUnit.SECONDS)).isTrue();
            businessThread.join(1_000L);
            takeoverThread.join(1_000L);
            assertThat(handler.findByClientId("survival")).isSameAs(newConnection);
        } finally {
            releaseHandler.countDown();
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("concurrent old cleanup and new bootstrap always leave new state registered")
    void cleanupAndTakeoverAreLinearized() throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, false);
        Set<String> initializedClients = ConcurrentHashMap.newKeySet();
        handler.setDisconnectListener((connection, activeGenerationEnded) -> {
            if (activeGenerationEnded) {
                initializedClients.remove(connection.getClientId());
            }
        });

        try {
            for (int i = 0; i < 20; i++) {
                String clientId = "client-" + i;
                ClientConnection oldConnection = connection(handler);
                ClientConnection newConnection = connection(handler);
                handler.activateAuthenticated(oldConnection, clientId,
                        () -> initializedClients.add(clientId));
                CountDownLatch start = new CountDownLatch(1);

                Thread disconnectThread = new Thread(() -> {
                    await(start);
                    handler.onClientDisconnected(oldConnection);
                });
                Thread takeoverThread = new Thread(() -> {
                    await(start);
                    handler.activateAuthenticated(newConnection, clientId,
                            () -> initializedClients.add(clientId));
                });
                disconnectThread.start();
                takeoverThread.start();
                start.countDown();
                disconnectThread.join(5_000L);
                takeoverThread.join(5_000L);

                assertThat(disconnectThread.isAlive()).isFalse();
                assertThat(takeoverThread.isAlive()).isFalse();
                assertThat(handler.findByClientId(clientId)).isSameAs(newConnection);
                assertThat(initializedClients).contains(clientId);
                handler.onClientDisconnected(newConnection);
            }
        } finally {
            handler.shutdown();
        }
    }

    private static ClientConnection connection(ServerNetworkHandler handler) {
        ClientConnection connection = new ClientConnection(new EmbeddedChannel());
        handler.onClientConnected(connection);
        return connection;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
