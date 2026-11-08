package com.nova.link.moderation;

import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModerationWorkflowTest {
    private ModerationManager manager() throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        return new ModerationManager(db, null);
    }

    private ModerationCase resolved(ModerationManager manager) {
        var value = manager.createReport("player-1", "Alex", "reporter", ReporterSource.OPERATOR,
                CaseSource.PANEL, "global", "spam", null, null, null, "alice");
        manager.assignCase(value.getId(), "alice", "alice");
        return manager.resolveCase(value.getId(), ResolutionAction.MUTED, "checked", "alice");
    }

    @Test
    void reassigningCannotBypassReviewerIsolation() throws Exception {
        var manager = manager();
        var value = resolved(manager);
        assertThatThrownBy(() -> manager.assignCase(value.getId(), "bob", "alice")).isInstanceOf(ModerationException.class);
        var appeal = manager.createAppeal(value.getId(), "player-1", "review", "reporter");
        assertThatThrownBy(() -> manager.assignCase(value.getId(), "bob", "alice")).isInstanceOf(ModerationException.class);
        assertThatThrownBy(() -> manager.reviewAppeal(appeal.getId(), AppealStatus.APPROVED, "alice", "self", "alice"))
                .isInstanceOf(ModerationException.class).hasMessageContaining("must differ");
        manager.reviewAppeal(appeal.getId(), AppealStatus.APPROVED, "bob", "checked", "bob");
        assertThat(manager.getCase(value.getId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThatThrownBy(() -> manager.assignCase(value.getId(), "alice", "bob")).isInstanceOf(ModerationException.class);
    }

    @Test
    void finalDecisionCannotBeOverwritten() throws Exception {
        var manager = manager();
        var value = resolved(manager);
        var appeal = manager.createAppeal(value.getId(), "player-1", "review", "reporter");
        manager.reviewAppeal(appeal.getId(), AppealStatus.DENIED, "bob", "final note", "bob");
        assertThatThrownBy(() -> manager.reviewAppeal(appeal.getId(), AppealStatus.APPROVED, "charlie", "overwrite", "charlie", true))
                .isInstanceOf(ModerationException.class);
        var saved = manager.getAppeal(appeal.getId()).orElseThrow();
        assertThat(saved.getReviewNote()).isEqualTo("final note");
        assertThat(saved.getReviewedBy()).isEqualTo("bob");
        assertThat(manager.getCase(value.getId()).orElseThrow().getClosedAt()).isNotNull();
    }

    @Test
    void escalationRequiresSeniorContinuationAndKeepsOriginalModerator() throws Exception {
        var manager = manager();
        var value = resolved(manager);
        var appeal = manager.createAppeal(value.getId(), "player-1", "review", "reporter");
        manager.reviewAppeal(appeal.getId(), AppealStatus.ESCALATED, "bob", "escalate", "bob");
        assertThat(manager.getCase(value.getId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.APPEALED);
        assertThatThrownBy(() -> manager.reviewAppeal(appeal.getId(), AppealStatus.APPROVED, "charlie", "checked", "charlie"))
                .isInstanceOf(ModerationException.class);
        assertThatThrownBy(() -> manager.reviewAppeal(appeal.getId(), AppealStatus.APPROVED, "alice", "self", "alice", true))
                .isInstanceOf(ModerationException.class);
        manager.reviewAppeal(appeal.getId(), AppealStatus.APPROVED, "charlie", "final", "charlie", true);
        assertThat(manager.getCase(value.getId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.CLOSED);
    }
}
