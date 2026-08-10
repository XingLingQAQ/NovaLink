package com.nova.chat.common.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Detailed unit tests for {@link MentionNotifier} dedup logic.
 *
 * <p>UX-DESIGN §4.2: the same mentioned player must not be spammed by repeated
 * mentions from the same author within {@link MentionNotifier#DEDUP_INTERVAL_MS}.
 * Dedup is keyed on (mentioned player, mentioner) — a different author mentioning
 * the same recipient in the same window still gets through.
 */
@DisplayName("MentionNotifier dedup")
class MentionNotifierDedupTest {

    private MentionNotifier notifier;
    private final UUID mentionerA = UUID.randomUUID();
    private final UUID mentionerB = UUID.randomUUID();
    private final UUID mentioned = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notifier = new MentionNotifier();
    }

    @Nested
    @DisplayName("shouldNotify dedup window")
    class DedupWindow {

        @Test
        @DisplayName("first notification from an author to a recipient is emitted")
        void firstNotificationEmitted() {
            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_000L)).isTrue();
        }

        @Test
        @DisplayName("repeat within the window from the same author is suppressed")
        void repeatWithinWindowSuppressed() {
            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_000L)).isTrue();
            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_000L + 100L)).isFalse();
            assertThat(notifier.shouldNotify(mentioned, mentionerA,
                    1_000L + MentionNotifier.DEDUP_INTERVAL_MS - 1L)).isFalse();
        }

        @Test
        @DisplayName("notification after the window from the same author is emitted again")
        void afterWindowEmittedAgain() {
            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_000L)).isTrue();
            long later = 1_000L + MentionNotifier.DEDUP_INTERVAL_MS;
            assertThat(notifier.shouldNotify(mentioned, mentionerA, later)).isTrue();
        }

        @Test
        @DisplayName("a second author mentioning the same recipient in the same window is emitted")
        void differentAuthorNotDeduped() {
            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_000L)).isTrue();
            // Same recipient, different author, same instant — must not be suppressed.
            assertThat(notifier.shouldNotify(mentioned, mentionerB, 1_000L)).isTrue();
        }

        @Test
        @DisplayName("the same author mentioning a different recipient in the same window is emitted")
        void differentRecipientNotDeduped() {
            UUID otherRecipient = UUID.randomUUID();
            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_000L)).isTrue();
            assertThat(notifier.shouldNotify(otherRecipient, mentionerA, 1_000L)).isTrue();
        }

        @Test
        @DisplayName("interleaved bursts from two authors collapse independently")
        void interleavedAuthors() {
            long t = 100L;
            // Author A fires twice within window → first emitted, second suppressed.
            assertThat(notifier.shouldNotify(mentioned, mentionerA, t)).isTrue();
            assertThat(notifier.shouldNotify(mentioned, mentionerA, t + 50L)).isFalse();
            // Author B fires once in the same window → emitted.
            assertThat(notifier.shouldNotify(mentioned, mentionerB, t + 75L)).isTrue();
            // Author A fires again well past the window → emitted again.
            assertThat(notifier.shouldNotify(mentioned, mentionerA,
                    t + MentionNotifier.DEDUP_INTERVAL_MS + 5L)).isTrue();
        }
    }

    @Nested
    @DisplayName("notifyOrSkip")
    class NotifyOrSkip {

        @Test
        @DisplayName("runs only once for repeated mentions within three seconds")
        void runsOnlyOnceWithinWindow() {
            AtomicInteger notifications = new AtomicInteger();

            notifier.notifyOrSkip(mentioned, mentionerA, 1_000L, notifications::incrementAndGet);
            notifier.notifyOrSkip(mentioned, mentionerA, 1_500L, notifications::incrementAndGet);
            notifier.notifyOrSkip(mentioned, mentionerA,
                    1_000L + MentionNotifier.DEDUP_INTERVAL_MS - 1L,
                    notifications::incrementAndGet);

            assertThat(notifications).hasValue(1);
        }

        @Test
        @DisplayName("runs again after the dedup window")
        void runsAgainAfterWindow() {
            AtomicInteger notifications = new AtomicInteger();

            notifier.notifyOrSkip(mentioned, mentionerA, 1_000L, notifications::incrementAndGet);
            notifier.notifyOrSkip(mentioned, mentionerA,
                    1_000L + MentionNotifier.DEDUP_INTERVAL_MS,
                    notifications::incrementAndGet);

            assertThat(notifications).hasValue(2);
        }

        @Test
        @DisplayName("different mentioners are notified independently")
        void differentMentionersIndependent() {
            AtomicInteger notifications = new AtomicInteger();

            notifier.notifyOrSkip(mentioned, mentionerA, 1_000L, notifications::incrementAndGet);
            notifier.notifyOrSkip(mentioned, mentionerB, 1_000L, notifications::incrementAndGet);
            notifier.notifyOrSkip(mentioned, mentionerA, 1_001L, notifications::incrementAndGet);

            assertThat(notifications).hasValue(2);
        }

        @Test
        @DisplayName("expired pairs are discarded without affecting active pairs")
        void expiredPairsDiscardedBehaviorally() {
            UUID expiredMentioner = UUID.randomUUID();
            AtomicInteger notifications = new AtomicInteger();
            long initial = 1_000L;

            notifier.notifyOrSkip(mentioned, expiredMentioner, initial, notifications::incrementAndGet);
            notifier.notifyOrSkip(mentioned, mentionerA,
                    initial + MentionNotifier.DEDUP_INTERVAL_MS - 1L,
                    notifications::incrementAndGet);
            notifier.notifyOrSkip(mentioned, mentionerB,
                    initial + MentionNotifier.DEDUP_INTERVAL_MS,
                    notifications::incrementAndGet);

            notifier.notifyOrSkip(mentioned, expiredMentioner,
                    initial + MentionNotifier.DEDUP_INTERVAL_MS,
                    notifications::incrementAndGet);
            notifier.notifyOrSkip(mentioned, mentionerA,
                    initial + MentionNotifier.DEDUP_INTERVAL_MS,
                    notifications::incrementAndGet);

            assertThat(notifications).hasValue(4);
        }
    }

    @Nested
    @DisplayName("clearDedup")
    class ClearDedup {

        @Test
        @DisplayName("clearing state allows an immediate repeat to pass again")
        void clearAllowsImmediateRepeat() {
            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_000L)).isTrue();
            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_010L)).isFalse();

            notifier.clearDedup();

            assertThat(notifier.shouldNotify(mentioned, mentionerA, 1_010L)).isTrue();
        }
    }

    @Nested
    @DisplayName("null safety")
    class NullSafety {

        @Test
        @DisplayName("null mentionedId is rejected")
        void nullMentioned() {
            assertThatThrownBy(() -> notifier.shouldNotify(null, mentionerA, 1L))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null notification callback is rejected")
        void nullCallback() {
            assertThatThrownBy(() -> notifier.notifyOrSkip(mentioned, mentionerA, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null mentionerId is rejected")
        void nullMentioner() {
            assertThatThrownBy(() -> notifier.shouldNotify(mentioned, null, 1L))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
