package com.nova.link.database;

import com.nova.link.moderation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class ModerationTransactionTest {
    @TempDir Path directory;

    private ModerationCase caseAt(CaseStatus status, long version) {
        return new ModerationCase("case-1", "player-1", "Alex", "reporter", ReporterSource.OPERATOR,
                CaseSource.PANEL, "global", "spam", null, status, "alice", ResolutionAction.MUTED,
                "checked", "hash", 1L, version, status == CaseStatus.CLOSED ? version : null);
    }

    private Appeal appeal(String id, AppealStatus status) {
        return new Appeal(id, "case-1", "player-1", "please review", status,
                status == AppealStatus.PENDING ? null : "bob", "review note", 10L, "hash", 1L);
    }

    private DatabaseProvider provider(boolean jdbc) throws Exception {
        DatabaseProvider provider = jdbc ? new SQLiteProvider(directory.resolve("cases.db").toString(), 1) : new MemoryProvider();
        provider.initialize();
        return provider;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleAssignmentCannotUndoResolution(boolean jdbc) throws Exception {
        var db = provider(jdbc);
        try {
            var before = caseAt(CaseStatus.UNDER_REVIEW, 1);
            db.saveModerationCase(before);
            assertThat(db.compareAndSetModerationCase(before, caseAt(CaseStatus.RESOLVED, 2))).isTrue();
            assertThat(db.compareAndSetModerationCase(before, caseAt(CaseStatus.UNDER_REVIEW, 3))).isFalse();
            assertThat(db.getModerationCase("case-1").orElseThrow().getStatus()).isEqualTo(CaseStatus.RESOLVED);
        } finally { db.shutdown(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentAppealsHaveOneWinner(boolean jdbc) throws Exception {
        var db = provider(jdbc);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var before = caseAt(CaseStatus.RESOLVED, 1);
            db.saveModerationCase(before);
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return db.createAppealAndUpdateCase(
                    appeal("appeal-1", AppealStatus.PENDING), before, caseAt(CaseStatus.APPEALED, 2)); });
            var second = pool.submit(() -> { start.await(); return db.createAppealAndUpdateCase(
                    appeal("appeal-2", AppealStatus.PENDING), before, caseAt(CaseStatus.APPEALED, 2)); });
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(db.countAppeals("PENDING")).isEqualTo(1);
            assertThat(db.getModerationCase("case-1").orElseThrow().getStatus()).isEqualTo(CaseStatus.APPEALED);
        } finally { pool.shutdownNow(); db.shutdown(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reviewAndCaseCommitTogetherAndRejectAnotherDecision(boolean jdbc) throws Exception {
        var db = provider(jdbc);
        try {
            var before = caseAt(CaseStatus.APPEALED, 1);
            var pending = appeal("appeal-1", AppealStatus.PENDING);
            db.saveModerationCase(before);
            db.saveAppeal(pending);
            assertThat(db.reviewAppealAndUpdateCase(pending, appeal("appeal-1", AppealStatus.APPROVED),
                    before, caseAt(CaseStatus.CLOSED, 2))).isTrue();
            assertThat(db.reviewAppealAndUpdateCase(pending, appeal("appeal-1", AppealStatus.DENIED),
                    before, caseAt(CaseStatus.CLOSED, 3))).isFalse();
            assertThat(db.getModerationCase("case-1").orElseThrow().getStatus()).isEqualTo(CaseStatus.CLOSED);
            assertThat(db.getAppeal("appeal-1").orElseThrow().getStatus()).isEqualTo(AppealStatus.APPROVED);
        } finally { db.shutdown(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentReviewsHaveOneWinner(boolean jdbc) throws Exception {
        var db = provider(jdbc);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var before = caseAt(CaseStatus.APPEALED, 1);
            var pending = appeal("appeal-1", AppealStatus.PENDING);
            db.saveModerationCase(before);
            db.saveAppeal(pending);
            CountDownLatch start = new CountDownLatch(1);
            var approved = pool.submit(() -> {
                start.await();
                return db.reviewAppealAndUpdateCase(pending, appeal("appeal-1", AppealStatus.APPROVED),
                        before, caseAt(CaseStatus.CLOSED, 2));
            });
            var denied = pool.submit(() -> {
                start.await();
                return db.reviewAppealAndUpdateCase(pending, appeal("appeal-1", AppealStatus.DENIED),
                        before, caseAt(CaseStatus.CLOSED, 3));
            });
            start.countDown();
            boolean approvalWon = approved.get(5, TimeUnit.SECONDS);
            assertThat(approvalWon ^ denied.get(5, TimeUnit.SECONDS)).isTrue();
            var storedCase = db.getModerationCase("case-1").orElseThrow();
            assertThat(storedCase.getStatus()).isEqualTo(CaseStatus.CLOSED);
            assertThat(storedCase.getClosedAt()).isEqualTo(approvalWon ? 2L : 3L);
            assertThat(db.getAppeal("appeal-1").orElseThrow().getStatus())
                    .isEqualTo(approvalWon ? AppealStatus.APPROVED : AppealStatus.DENIED);
        } finally { pool.shutdownNow(); db.shutdown(); }
    }

    @Test
    void insertAndReviewFailuresRollbackCaseOnTheSameConnection() throws Exception {
        var db = (SQLiteProvider) provider(true);
        try {
            var resolved = caseAt(CaseStatus.RESOLVED, 1);
            db.saveModerationCase(resolved);
            try (var conn = db.getDataSource().getConnection(); var sql = conn.createStatement()) {
                sql.execute("CREATE TRIGGER reject_appeal BEFORE INSERT ON appeals BEGIN SELECT RAISE(ABORT, 'injected insert failure'); END");
            }
            var pending = appeal("appeal-1", AppealStatus.PENDING);
            assertThatThrownBy(() -> db.createAppealAndUpdateCase(pending, resolved, caseAt(CaseStatus.APPEALED, 2)))
                    .isInstanceOf(DatabaseException.class);
            assertThat(db.getModerationCase("case-1").orElseThrow().getStatus()).isEqualTo(CaseStatus.RESOLVED);
            assertThat(db.getAppeal("appeal-1")).isEmpty();
            try (var conn = db.getDataSource().getConnection(); var sql = conn.createStatement()) {
                sql.execute("DROP TRIGGER reject_appeal");
            }
            assertThat(db.createAppealAndUpdateCase(pending, resolved, caseAt(CaseStatus.APPEALED, 2))).isTrue();
            try (var conn = db.getDataSource().getConnection(); var sql = conn.createStatement()) {
                sql.execute("CREATE TRIGGER reject_review BEFORE UPDATE ON appeals BEGIN SELECT RAISE(ABORT, 'injected review failure'); END");
            }
            assertThatThrownBy(() -> db.reviewAppealAndUpdateCase(pending, appeal("appeal-1", AppealStatus.DENIED),
                    caseAt(CaseStatus.APPEALED, 2), caseAt(CaseStatus.CLOSED, 3))).isInstanceOf(DatabaseException.class);
            assertThat(db.getModerationCase("case-1").orElseThrow().getStatus()).isEqualTo(CaseStatus.APPEALED);
            assertThat(db.getAppeal("appeal-1").orElseThrow().getStatus()).isEqualTo(AppealStatus.PENDING);
        } finally { db.shutdown(); }
    }
}
