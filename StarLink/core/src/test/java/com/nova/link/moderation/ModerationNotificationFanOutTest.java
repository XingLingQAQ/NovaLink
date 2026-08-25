package com.nova.link.moderation;

import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PanelRole;
import com.nova.link.auth.PanelUserCredentials;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.Notification;
import com.nova.link.notification.NotificationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PANEL-014 — production producer for directed notifications.
 *
 * <p>Until now {@link NotificationStore#createDirectedNotification} had ZERO
 * production callers: the entire per-user notification machinery (recipient
 * persistence, per-user read state, directed WS delivery) never saw real data
 * because nothing ever created a directed notification outside the E2E test
 * that bypassed the store. This suite locks in the producer wiring:
 * {@link ModerationManager} fans out one directed notification per
 * ADMIN-or-above panel user whenever a case or appeal is filed.
 *
 * <p>Contract under test:
 * <ol>
 *   <li><b>N-per-filing</b> — one filing event produces exactly N directed
 *       notifications where N = number of ADMIN/SUPER_ADMIN panel users
 *       (VIEWERs excluded), discovered via the AuthManager role model.</li>
 *   <li><b>Correct recipients + content</b> — each notification carries the
 *       admin's username as recipient and summarizes the filing.</li>
 *   <li><b>Failure tolerance</b> — an enqueuing failure (provider throw,
 *       throwing recipient supplier) never fails the primary moderation
 *       operation.</li>
 *   <li><b>Unwired = silent</b> — with no store/supplier set, filing behaves
 *       exactly as before (no notifications, no errors).</li>
 * </ol>
 *
 * <p>Requirements: PANEL-014 directed-notification production producer
 */
@DisplayName("PANEL-014: ModerationManager fans out directed notifications on case/appeal filing")
class ModerationNotificationFanOutTest {

    private MemoryProvider db;
    private NotificationStore store;
    private ModerationManager manager;

    @BeforeEach
    void setUp() throws DatabaseException {
        db = new MemoryProvider();
        db.initialize();
        store = new NotificationStore(db);
        manager = new ModerationManager(db, null);
        manager.setNotificationStore(store);
    }

    /** Registers panel users mirroring the production config model. */
    private AuthManager seedPanelUsers() {
        AuthManager authManager = new AuthManager(new IpBanManager(5, 60_000));
        authManager.registerSuperAdmin("root", AuthManager.hashPassword("pw-root"));
        authManager.registerPanelUser(new PanelUserCredentials(
                "mod-alice", AuthManager.hashPassword("pw-alice"), PanelRole.ADMIN));
        authManager.registerPanelUser(new PanelUserCredentials(
                "viewer-bob", AuthManager.hashPassword("pw-bob"), PanelRole.VIEWER));
        return authManager;
    }

    private List<Notification> persistedNotifications() throws DatabaseException {
        return db.getNotifications(0, 100, false);
    }

    // ====================== N-per-filing + recipients ======================

    @Test
    @DisplayName("filing a case creates exactly one directed notification per ADMIN+ user, none for VIEWERs")
    void caseFilingFansOutPerAdmin() throws Exception {
        AuthManager authManager = seedPanelUsers();
        manager.setPanelAdminUsernames(() ->
                authManager.getPanelUsernamesWithRoleAtLeast(PanelRole.ADMIN));

        manager.createReport("player-1", "Steve", "panel-op", ReporterSource.OPERATOR,
                CaseSource.PANEL, "survival-chat", "[SPAM] repeated ads",
                null, null, null, "panel-op");

        List<Notification> all = persistedNotifications();
        assertThat(all).as("one notification per ADMIN+ user (root, mod-alice); VIEWER excluded")
                .hasSize(2);

        List<String> recipients = all.stream().map(Notification::getRecipient).sorted().toList();
        assertThat(recipients)
                .as("recipients come from the role model, not a hardcoded list")
                .containsExactly("mod-alice", "root");
        assertThat(all)
                .allSatisfy(n -> {
                    assertThat(n.getRecipient()).isNotBlank();
                    assertThat(n.getTitle()).startsWith("New Case #");
                    assertThat(n.getLevel()).isEqualTo(Notification.LEVEL_INFO);
                });
        // Body summarizes subject + reporter.
        assertThat(all.get(0).getMessage()).contains("player-1").contains("panel-op");

        // Directed only: nothing leaked into the broadcast stream.
        assertThat(all).noneMatch(n -> n.getRecipient() == null);
    }

    @Test
    @DisplayName("filing an appeal creates one WARNING-level directed notification per ADMIN+ user")
    void appealFilingFansOutWarningPerAdmin() throws Exception {
        AuthManager authManager = seedPanelUsers();
        manager.setPanelAdminUsernames(() ->
                authManager.getPanelUsernamesWithRoleAtLeast(PanelRole.ADMIN));

        ModerationCase resolved = manager.createReport("player-2", null, "op",
                ReporterSource.OPERATOR, CaseSource.PANEL, null, "griefing",
                null, null, null, "op");
        manager.resolveCase(resolved.getId(), ResolutionAction.MUTED, "30m", "op");

        int beforeAppeal = persistedNotifications().size();
        Appeal appeal = manager.createAppeal(resolved.getId(), "player-2",
                "I did not grief", "op");

        List<Notification> all = persistedNotifications();
        assertThat(all).hasSize(beforeAppeal + 2);

        List<Notification> appealNotes = all.stream()
                .filter(n -> n.getTitle().startsWith("New Appeal #"))
                .toList();
        assertThat(appealNotes).hasSize(2);
        assertThat(appealNotes)
                .allSatisfy(n -> {
                    assertThat(n.getLevel()).isEqualTo(Notification.LEVEL_WARNING);
                    assertThat(n.getMessage()).contains(resolved.getId()).contains("player-2");
                    assertThat(n.getRecipient()).isNotBlank();
                });
        assertThat(appeal.getId()).isNotBlank();
    }

    // ====================== failure tolerance ======================

    @Test
    @DisplayName("a provider persistence failure during fan-out does not fail case creation")
    void providerFailureDoesNotFailCaseCreation() throws Exception {
        AuthManager authManager = seedPanelUsers();
        manager.setPanelAdminUsernames(() ->
                authManager.getPanelUsernamesWithRoleAtLeast(PanelRole.ADMIN));

        // A store whose provider throws on every save — createDirectedNotification
        // swallows DatabaseException internally; this asserts the primary op
        // still succeeds end-to-end through the real seam.
        MemoryProvider brokenDb = new MemoryProvider() {
            @Override
            public void saveNotification(Notification notification) throws DatabaseException {
                throw new DatabaseException("simulated persistence outage");
            }
        };
        brokenDb.initialize();
        NotificationStore brokenStore = new NotificationStore(brokenDb);
        manager.setNotificationStore(brokenStore);

        ModerationCase created = manager.createReport("player-3", null, "op",
                ReporterSource.OPERATOR, CaseSource.PANEL, null, "spam bot ring",
                null, null, null, "op");

        assertThat(created.getId()).as("case creation must still succeed").isNotBlank();
        assertThat(manager.getCase(created.getId())).as("case must be persisted").isPresent();

        // The throwing supplier variant: recipient discovery blowing up must
        // also be swallowed.
        manager.setNotificationStore(store);
        manager.setPanelAdminUsernames(() -> {
            throw new IllegalStateException("auth backend unavailable");
        });
        ModerationCase second = manager.createReport("player-4", null, "op",
                ReporterSource.OPERATOR, CaseSource.PANEL, null, "more spam",
                null, null, null, "op");
        assertThat(second.getId()).as("filing succeeds even when recipient lookup throws")
                .isNotBlank();
    }

    @Test
    @DisplayName("per-recipient exception inside the fan-out loop is isolated to that recipient")
    void perRecipientFailureIsIsolated() throws Exception {
        // Store stub that throws only for one specific recipient — mirrors a
        // partial WS/DB outage. The other admins must still receive theirs.
        NotificationStore flakyStore = new NotificationStore(db) {
            @Override
            public Notification createDirectedNotification(String title, String message,
                                                           String level, String recipient) {
                if ("root".equals(recipient)) {
                    throw new RuntimeException("boom for root");
                }
                return super.createDirectedNotification(title, message, level, recipient);
            }
        };
        manager.setNotificationStore(flakyStore);
        List<String> recipients = List.of("mod-alice", "root");
        manager.setPanelAdminUsernames(() -> recipients.stream()
                .filter(u -> !u.isBlank()).distinct().sorted().toList());

        ModerationCase created = manager.createReport("player-5", null, "op",
                ReporterSource.OPERATOR, CaseSource.PANEL, null, "xray",
                null, null, null, "op");
        assertThat(created.getId()).isNotBlank();

        List<Notification> all = persistedNotifications();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getRecipient()).isEqualTo("mod-alice");
    }

    // ====================== unwired / empty behavior ======================

    @Test
    @DisplayName("no recipients configured → filing succeeds with zero notifications")
    void emptyRecipientListSkipsSilently() throws Exception {
        AuthManager authManager = new AuthManager(new IpBanManager(5, 60_000));
        authManager.registerPanelUser(new PanelUserCredentials(
                "viewer-only", AuthManager.hashPassword("pw"), PanelRole.VIEWER));
        manager.setPanelAdminUsernames(() ->
                authManager.getPanelUsernamesWithRoleAtLeast(PanelRole.ADMIN));

        ModerationCase created = manager.createReport("player-6", null, "op",
                ReporterSource.OPERATOR, CaseSource.PANEL, null, "minor",
                null, null, null, "op");

        assertThat(created.getId()).isNotBlank();
        assertThat(persistedNotifications()).isEmpty();
    }

    @Test
    @DisplayName("store wired but no supplier (legacy construction) → no fan-out, no error")
    void missingSupplierDisablesFanOut() throws Exception {
        ModerationCase created = manager.createReport("player-7", null, "op",
                ReporterSource.OPERATOR, CaseSource.PANEL, null, "legacy path",
                null, null, null, "op");

        assertThat(created.getId()).isNotBlank();
        assertThat(persistedNotifications())
                .as("null supplier must disable the fan-out entirely")
                .isEmpty();
    }
}
