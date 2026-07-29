package com.nova.chat.folia;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.folia.chat.PlayerChatState;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Folia thread safety.
 * 
 * **Feature: novachat-platform-extensions, Property 17: Folia Thread Safety**
 * **Validates: Requirements 2.3**
 * 
 * Tests that player operations in Folia are thread-safe and can be executed
 * concurrently without data corruption or race conditions.
 */
class FoliaThreadSafetyPropertyTest {

    /**
     * Property 17: Folia Thread Safety - PlayerChatState Concurrent Access
     * 
     * *For any* player operation in Folia, the operation should execute safely
     * when accessed from multiple threads concurrently.
     * 
     * This test verifies that PlayerChatState can be safely accessed and modified
     * from multiple threads without data corruption.
     * 
     * **Validates: Requirements 2.3**
     */
    @Property(tries = 100)
    void playerChatStateConcurrentAccessIsSafe(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String initialChannel,
            @ForAll @IntRange(min = 2, max = 10) int numThreads,
            @ForAll @IntRange(min = 10, max = 50) int operationsPerThread
    ) throws InterruptedException, ExecutionException {
        // Create a shared state
        PlayerChatState state = new PlayerChatState(playerId, initialChannel, ChatMode.HYBRID);
        
        // Use ConcurrentHashMap to simulate the playerStates map in AsyncChatInterceptor
        Map<UUID, PlayerChatState> playerStates = new ConcurrentHashMap<>();
        playerStates.put(playerId, state);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger successfulOperations = new AtomicInteger(0);
        
        List<Future<?>> futures = new ArrayList<>();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    for (int i = 0; i < operationsPerThread; i++) {
                        // Simulate various operations that would happen in Folia
                        PlayerChatState currentState = playerStates.get(playerId);
                        if (currentState != null) {
                            // Read operations
                            String channel = currentState.getActiveChannel();
                            ChatMode mode = currentState.getChatMode();
                            boolean overridden = currentState.isModeOverridden();
                            
                            // Write operations (thread-safe due to volatile fields)
                            currentState.setActiveChannel("channel_" + threadId + "_" + i);
                            
                            successfulOperations.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for completion
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify no exceptions occurred
        for (Future<?> future : futures) {
            future.get(); // Will throw if any thread had an exception
        }
        
        // Verify all operations completed
        assertThat(successfulOperations.get()).isEqualTo(numThreads * operationsPerThread);
        
        // Verify state is still valid (not corrupted)
        PlayerChatState finalState = playerStates.get(playerId);
        assertThat(finalState).isNotNull();
        assertThat(finalState.getPlayerId()).isEqualTo(playerId);
        assertThat(finalState.getActiveChannel()).isNotNull();
        assertThat(finalState.getChatMode()).isNotNull();
    }

    /**
     * Property: ConcurrentHashMap operations are atomic
     * 
     * *For any* set of concurrent computeIfAbsent operations, each player should
     * have exactly one state created.
     * 
     * **Validates: Requirements 2.3**
     */
    @Property(tries = 100)
    void computeIfAbsentIsAtomicForPlayerStates(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String defaultChannel,
            @ForAll @IntRange(min = 2, max = 10) int numThreads
    ) throws InterruptedException, ExecutionException {
        Map<UUID, PlayerChatState> playerStates = new ConcurrentHashMap<>();
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        AtomicInteger statesCreated = new AtomicInteger(0);
        List<Future<PlayerChatState>> futures = new ArrayList<>();
        
        for (int t = 0; t < numThreads; t++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // This simulates getOrCreateState in AsyncChatInterceptor
                    return playerStates.computeIfAbsent(playerId, uuid -> {
                        statesCreated.incrementAndGet();
                        return new PlayerChatState(uuid, defaultChannel, ChatMode.HYBRID);
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } finally {
                    doneLatch.countDown();
                }
            }));
        }
        
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // All threads should get the same state instance
        PlayerChatState firstState = futures.get(0).get();
        for (Future<PlayerChatState> future : futures) {
            assertThat(future.get()).isSameAs(firstState);
        }
        
        // Only one state should have been created
        assertThat(statesCreated.get()).isEqualTo(1);
        
        // Map should contain exactly one entry
        assertThat(playerStates).hasSize(1);
    }

    /**
     * Property: Toggle mode is thread-safe
     * 
     * *For any* number of concurrent toggle operations, the final mode should
     * be deterministic based on the number of toggles (even = original, odd = opposite).
     * 
     * **Validates: Requirements 2.3**
     */
    @Property(tries = 100)
    void toggleModeIsThreadSafe(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String channel,
            @ForAll ChatMode initialMode,
            @ForAll @IntRange(min = 1, max = 20) int numToggles
    ) throws InterruptedException {
        PlayerChatState state = new PlayerChatState(playerId, channel, initialMode);
        
        ExecutorService executor = Executors.newFixedThreadPool(numToggles);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numToggles);
        
        for (int i = 0; i < numToggles; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    state.toggleMode(); // synchronized method
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // After numToggles toggles:
        // - Even number of toggles -> back to initial mode
        // - Odd number of toggles -> opposite mode
        ChatMode expectedMode = (numToggles % 2 == 0) ? initialMode : 
            (initialMode == ChatMode.HYBRID ? ChatMode.REPLACE : ChatMode.HYBRID);
        
        assertThat(state.getChatMode()).isEqualTo(expectedMode);
        assertThat(state.isModeOverridden()).isTrue();
    }

    /**
     * Property: State copy is independent
     * 
     * *For any* PlayerChatState, modifications to a copy should not affect the original.
     * This is important for thread safety when passing state between threads.
     * 
     * **Validates: Requirements 2.3**
     */
    @Property(tries = 100)
    void stateCopyIsIndependent(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String originalChannel,
            @ForAll("validChannelIds") String newChannel,
            @ForAll ChatMode originalMode
    ) {
        // Ensure channels are different for meaningful test
        Assume.that(!originalChannel.equals(newChannel));
        
        PlayerChatState original = new PlayerChatState(playerId, originalChannel, originalMode);
        PlayerChatState copy = original.copy();
        
        // Modify the copy
        copy.setActiveChannel(newChannel);
        copy.toggleMode();
        
        // Original should be unchanged
        assertThat(original.getActiveChannel()).isEqualTo(originalChannel);
        assertThat(original.getChatMode()).isEqualTo(originalMode);
        assertThat(original.isModeOverridden()).isFalse();
        
        // Copy should have new values
        assertThat(copy.getActiveChannel()).isEqualTo(newChannel);
        assertThat(copy.getChatMode()).isNotEqualTo(originalMode);
        assertThat(copy.isModeOverridden()).isTrue();
    }

    /**
     * Property: Volatile fields provide visibility across threads
     * 
     * *For any* write to a volatile field, subsequent reads from other threads
     * should see the updated value.
     * 
     * **Validates: Requirements 2.3**
     */
    @Property(tries = 100)
    void volatileFieldsProvideVisibility(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String initialChannel,
            @ForAll("validChannelIds") String updatedChannel
    ) throws InterruptedException, ExecutionException {
        Assume.that(!initialChannel.equals(updatedChannel));
        
        PlayerChatState state = new PlayerChatState(playerId, initialChannel, ChatMode.HYBRID);
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch writeDone = new CountDownLatch(1);
        
        // Writer thread
        Future<?> writer = executor.submit(() -> {
            state.setActiveChannel(updatedChannel);
            writeDone.countDown();
        });
        
        // Reader thread - waits for write to complete
        Future<String> reader = executor.submit(() -> {
            try {
                writeDone.await();
                // Small delay to ensure memory visibility
                Thread.sleep(10);
                return state.getActiveChannel();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
        
        writer.get();
        String readValue = reader.get();
        
        executor.shutdown();
        
        // Reader should see the updated value
        assertThat(readValue).isEqualTo(updatedChannel);
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<UUID> validUUIDs() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> validChannelIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_', '-')
                .ofMinLength(1)
                .ofMaxLength(32);
    }
}
