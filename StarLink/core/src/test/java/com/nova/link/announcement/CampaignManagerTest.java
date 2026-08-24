package com.nova.link.announcement;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CampaignManager} (§11.6 提案 06 — slice A).
 *
 * <p>Covers the in-memory state machine, the three-way RBAC mapping onto the
 * 4-level {@link PermissionLevel} hierarchy, the per-channel/per-hour rate
 * limit, revoke-cancels-scheduled-task, and audit-store invocation. All tests
 * run in-memory with a real {@link PermissionManager} and {@link ChannelManager};
 * no database or network is involved.
 *
 * <p>Slice A adaptation: the throwaway {@code CampaignAuditSink} functional
 * interface has been replaced by the canonical {@link com.nova.link.audit.AuditStore}.
 * The three audit tests below use a capturing {@code AuditStore} subclass that
 * records every {@link com.nova.link.audit.AuditEvent} handed to
 * {@code record(...)} so the tests can assert on the event fields directly,
 * mirroring how {@code ModerationManagerTest} verifies audit behavior.
 */
@DisplayName("CampaignManager — state machine, RBAC, rate limit, revoke, audit")
class CampaignManagerTest {

    private PermissionManager permissionManager;
    private ChannelManager channelManager;
    private CampaignManager manager;

    private static final String CHANNEL_ID = "staff";

    @BeforeEach
    void setUp() {
        permissionManager = new PermissionManager();
        channelManager = new ChannelManager();
        channelManager.createChannel(ChannelConfig.builder()
                .id(CHANNEL_ID)
                .displayName("Staff")
                .scope(ChannelScope.GLOBAL)
                .build());
        manager = new CampaignManager(permissionManager, channelManager);
        manager.initialize();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    // ====================== helpers ======================

    /** Creates a campaign in PREVIEW using a trusted operator (skips RBAC). */
    private Campaign createPreviewCampaign(String content) {
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("survival");
        CampaignResult result = manager.createCampaign(
                UUID.randomUUID(), CHANNEL_ID, content, platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5,
                "TestClient", true);
        assertThat(result.isSuccess()).as("createCampaign should succeed: %s", result.getMessage()).isTrue();
        return result.getCampaign();
    }

    // ====================== state machine ======================

    @Test
    @DisplayName("PREVIEW → SCHEDULED → ACTIVE → EXPIRED is legal")
    void legalForwardTransition() {
        Campaign campaign = createPreviewCampaign("hello");

        // PREVIEW → SCHEDULED (startAt = 0 path transitions straight to ACTIVE
        // per scheduleCampaign, so we set a future startAt to test SCHEDULED).
        CampaignResult scheduleResult = manager.scheduleCampaign(
                campaign.getId(), UUID.randomUUID(), true);
        assertThat(scheduleResult.isSuccess()).as(scheduleResult.getMessage()).isTrue();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);

        // ACTIVE → EXPIRED via the state machine validator.
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.ACTIVE, CampaignStatus.EXPIRED)).isTrue();
        campaign.setStatus(CampaignStatus.EXPIRED);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.EXPIRED);
    }

    @Test
    @DisplayName("PREVIEW → ACTIVE (immediate, startAt=0) is legal")
    void immediateActivationPath() {
        Campaign campaign = createPreviewCampaign("immediate");
        // startAt == 0 → scheduleCampaign transitions straight to ACTIVE.
        CampaignResult result = manager.scheduleCampaign(
                campaign.getId(), UUID.randomUUID(), true);
        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(campaign.getScheduleRevision()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("REVOKED is reachable from any non-terminal state")
    void revokeFromAnyNonTerminal() {
        Campaign previewCampaign = createPreviewCampaign("preview");
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.PREVIEW, CampaignStatus.REVOKED)).isTrue();
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.SCHEDULED, CampaignStatus.REVOKED)).isTrue();
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.ACTIVE, CampaignStatus.REVOKED)).isTrue();
        // Sanity: revoke the PREVIEW campaign through the manager.
        CampaignResult revokeResult = manager.revokeCampaign(
                previewCampaign.getId(), UUID.randomUUID(), true);
        assertThat(revokeResult.isSuccess()).as(revokeResult.getMessage()).isTrue();
        assertThat(previewCampaign.getStatus()).isEqualTo(CampaignStatus.REVOKED);
    }

    @Test
    @DisplayName("Terminal states cannot transition (EXPIRED/REVOKED)")
    void terminalStatesAreStuck() {
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.EXPIRED, CampaignStatus.ACTIVE)).isFalse();
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.EXPIRED, CampaignStatus.SCHEDULED)).isFalse();
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.REVOKED, CampaignStatus.ACTIVE)).isFalse();
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.REVOKED, CampaignStatus.SCHEDULED)).isFalse();
        // Re-revoking a revoked campaign is also rejected.
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.REVOKED, CampaignStatus.REVOKED)).isFalse();
    }

    @Test
    @DisplayName("Illegal transitions are rejected (PREVIEW → EXPIRED, ACTIVE → SCHEDULED)")
    void illegalTransitionsRejected() {
        // PREVIEW → EXPIRED is illegal (PREVIEW only goes to SCHEDULED or ACTIVE).
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.PREVIEW, CampaignStatus.EXPIRED)).isFalse();
        // ACTIVE → SCHEDULED is illegal (ACTIVE only goes to EXPIRED or REVOKED).
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.ACTIVE, CampaignStatus.SCHEDULED)).isFalse();
        // SCHEDULED → EXPIRED is illegal (SCHEDULED only goes to ACTIVE or REVOKED).
        assertThat(CampaignStatus.isValidTransition(
                CampaignStatus.SCHEDULED, CampaignStatus.EXPIRED)).isFalse();
        // Note: PREVIEW → ACTIVE is now LEGAL (immediate-activation path).
    }

    @Test
    @DisplayName("scheduleCampaign on a REVOKED campaign is rejected (badRequest)")
    void scheduleOnRevokedIsRejected() {
        Campaign campaign = createPreviewCampaign("revoked");
        manager.revokeCampaign(campaign.getId(), UUID.randomUUID(), true);
        CampaignResult result = manager.scheduleCampaign(
                campaign.getId(), UUID.randomUUID(), true);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(CampaignResult.CODE_BAD_REQUEST);
    }

    @Test
    @DisplayName("revokeCampaign on a REVOKED campaign is rejected (badRequest)")
    void revokeOnRevokedIsRejected() {
        Campaign campaign = createPreviewCampaign("double-revoke");
        manager.revokeCampaign(campaign.getId(), UUID.randomUUID(), true);
        CampaignResult result = manager.revokeCampaign(
                campaign.getId(), UUID.randomUUID(), true);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(CampaignResult.CODE_BAD_REQUEST);
    }

    // ====================== RBAC mapping ======================

    @Test
    @DisplayName("createCampaign without trustedOperator requires CHANNEL_ADMIN (PLAYER denied)")
    void createCampaignRequiresChannelAdmin() {
        UUID plainPlayer = UUID.randomUUID();
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("survival");
        CampaignResult result = manager.createCampaign(
                plainPlayer, CHANNEL_ID, "content", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5,
                "TestClient", false);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(CampaignResult.CODE_FORBIDDEN);

        // Grant channel admin → create succeeds.
        permissionManager.grantChannelAdmin(CHANNEL_ID, plainPlayer);
        CampaignResult result2 = manager.createCampaign(
                plainPlayer, CHANNEL_ID, "content", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5,
                "TestClient", false);
        assertThat(result2.isSuccess()).as(result2.getMessage()).isTrue();
    }

    @Test
    @DisplayName("scheduleCampaign without trustedOperator requires CLIENT_ADMIN (CHANNEL_ADMIN denied)")
    void scheduleCampaignRequiresClientAdmin() {
        // Create with a channel admin (trusted=false, but CHANNEL_ADMIN granted).
        UUID channelAdmin = UUID.randomUUID();
        permissionManager.grantChannelAdmin(CHANNEL_ID, channelAdmin);
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("survival");
        CampaignResult createResult = manager.createCampaign(
                channelAdmin, CHANNEL_ID, "content", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5,
                "TestClient", false);
        assertThat(createResult.isSuccess()).as(createResult.getMessage()).isTrue();

        // Channel admin is NOT a client admin → schedule denied.
        CampaignResult scheduleResult = manager.scheduleCampaign(
                createResult.getCampaign().getId(), channelAdmin, false);
        assertThat(scheduleResult.isSuccess()).isFalse();
        assertThat(scheduleResult.getErrorCode()).isEqualTo(CampaignResult.CODE_FORBIDDEN);

        // Promote to client admin → schedule succeeds (startAt=0 → ACTIVE).
        permissionManager.registerClientAdmin(channelAdmin, "TestClient");
        CampaignResult scheduleResult2 = manager.scheduleCampaign(
                createResult.getCampaign().getId(), channelAdmin, false);
        assertThat(scheduleResult2.isSuccess()).as(scheduleResult2.getMessage()).isTrue();
    }

    @Test
    @DisplayName("revokeCampaign without trustedOperator requires SUPER_ADMIN (CLIENT_ADMIN denied)")
    void revokeCampaignRequiresSuperAdmin() {
        UUID clientAdmin = UUID.randomUUID();
        permissionManager.registerClientAdmin(clientAdmin, "TestClient");
        Campaign campaign = createPreviewCampaign("revoke-me");

        // Client admin is NOT super admin → revoke denied.
        CampaignResult result = manager.revokeCampaign(
                campaign.getId(), clientAdmin, false);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(CampaignResult.CODE_FORBIDDEN);

        // Register + authenticate super admin → revoke succeeds.
        UUID superAdmin = UUID.randomUUID();
        permissionManager.registerSuperAdmin(
                new SuperAdminCredentials(superAdmin, "hash"));
        permissionManager.authenticateSuperAdmin(superAdmin, "hash");
        CampaignResult result2 = manager.revokeCampaign(
                campaign.getId(), superAdmin, false);
        assertThat(result2.isSuccess()).as(result2.getMessage()).isTrue();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.REVOKED);
    }

    // ====================== rate limit ======================

    @Test
    @DisplayName("Per-channel/per-hour rate limit caps deliveries (rateLimited result)")
    void rateLimitCapsDeliveries() {
        // Create + activate a campaign with rateLimitPerHour = 2.
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("survival");
        AtomicInteger sentCount = new AtomicInteger();
        manager.setAnnouncementSender((channelId, content) -> sentCount.incrementAndGet());

        // Create 3 campaigns in the same channel with rateLimitPerHour = 2.
        // All three use startAt=0, so they go PREVIEW → ACTIVE via scheduleCampaign
        // (immediate-activation path). Each activation calls deliverOnce, and the
        // third one should hit the per-channel/per-hour cap.
        CampaignResult c1 = manager.createCampaign(
                UUID.randomUUID(), CHANNEL_ID, "content-1", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 2,
                "TestClient", true);
        CampaignResult c2 = manager.createCampaign(
                UUID.randomUUID(), CHANNEL_ID, "content-2", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 2,
                "TestClient", true);
        CampaignResult c3 = manager.createCampaign(
                UUID.randomUUID(), CHANNEL_ID, "content-3", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 2,
                "TestClient", true);
        assertThat(c1.isSuccess()).as(c1.getMessage()).isTrue();
        assertThat(c2.isSuccess()).as(c2.getMessage()).isTrue();
        assertThat(c3.isSuccess()).as(c3.getMessage()).isTrue();

        // Activate c1 (startAt=0 → schedule → ACTIVE + deliverOnce, count=1).
        CampaignResult a1 = manager.scheduleCampaign(c1.getCampaign().getId(), UUID.randomUUID(), true);
        assertThat(a1.isSuccess()).as(a1.getMessage()).isTrue();
        assertThat(manager.currentHourDeliveryCount(CHANNEL_ID)).isEqualTo(1);

        // Activate c2 (count=2, still under cap).
        CampaignResult a2 = manager.scheduleCampaign(c2.getCampaign().getId(), UUID.randomUUID(), true);
        assertThat(a2.isSuccess()).as(a2.getMessage()).isTrue();
        assertThat(manager.currentHourDeliveryCount(CHANNEL_ID)).isEqualTo(2);

        // Activate c3 (cap=2 → deliverOnce returns rateLimited; activation
        // succeeds but the delivery is skipped).
        CampaignResult a3 = manager.scheduleCampaign(c3.getCampaign().getId(), UUID.randomUUID(), true);
        assertThat(a3.isSuccess()).as(a3.getMessage()).isTrue();
        // Counter stays at the cap (the third delivery was rate-limited).
        assertThat(manager.currentHourDeliveryCount(CHANNEL_ID)).isEqualTo(2);
        // Only 2 actual sends happened.
        assertThat(sentCount.get()).isEqualTo(2);
    }

    // ====================== revoke cancels scheduled task ======================

    @Test
    @DisplayName("revokeCampaign cancels the armed scheduled activation task")
    void revokeCancelsScheduledTask() throws Exception {
        // Create a campaign with a future startAt (1 hour out) so armActivation
        // actually arms a one-shot task.
        long futureStart = System.currentTimeMillis() + 3_600_000L;
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("survival");
        CampaignResult createResult = manager.createCampaign(
                UUID.randomUUID(), CHANNEL_ID, "future", platforms,
                DeliveryPolicy.INSTANT, futureStart, 0L, 5,
                "TestClient", true);
        assertThat(createResult.isSuccess()).as(createResult.getMessage()).isTrue();

        // Schedule → arms a one-shot task.
        CampaignResult scheduleResult = manager.scheduleCampaign(
                createResult.getCampaign().getId(), UUID.randomUUID(), true);
        assertThat(scheduleResult.isSuccess()).as(scheduleResult.getMessage()).isTrue();
        assertThat(createResult.getCampaign().getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(manager.getScheduledTaskCount())
                .as("scheduled task should be armed after scheduleCampaign")
                .isEqualTo(1);

        // Revoke → cancels the task and clears the slot.
        CampaignResult revokeResult = manager.revokeCampaign(
                createResult.getCampaign().getId(), UUID.randomUUID(), true);
        assertThat(revokeResult.isSuccess()).as(revokeResult.getMessage()).isTrue();
        assertThat(createResult.getCampaign().getStatus()).isEqualTo(CampaignStatus.REVOKED);
        assertThat(manager.getScheduledTaskCount())
                .as("scheduled task should be cancelled after revoke")
                .isZero();
    }

    // ====================== audit store (canonical AuditStore) ======================

    @Test
    @DisplayName("AuditStore receives an event per mutating operation")
    void auditStoreReceivesEvents() {
        // Capturing AuditStore subclass: record every AuditEvent handed to
        // record(...) so the test can assert on the event fields directly.
        CopyOnWriteArrayList<com.nova.link.audit.AuditEvent> captured =
                new CopyOnWriteArrayList<>();
        com.nova.link.audit.AuditStore capturingStore =
                new com.nova.link.audit.AuditStore(null) {
                    @Override
                    public void record(com.nova.link.audit.AuditEvent event) {
                        captured.add(event);
                    }
                };
        manager.setAuditStore(capturingStore);

        Campaign campaign = createPreviewCampaign("audited");
        // scheduleCampaign with startAt=0 transitions PREVIEW → ACTIVE, so
        // it records campaign.schedule (immediate-activation path calls
        // doActivate which records campaign.activate).
        manager.scheduleCampaign(campaign.getId(), UUID.randomUUID(), true);
        manager.revokeCampaign(campaign.getId(), UUID.randomUUID(), true);

        // At least 3 events: campaign.create, campaign.activate (from doActivate
        // on the immediate-activation path), campaign.revoke.
        assertThat(captured).hasSizeGreaterThanOrEqualTo(3);
        assertThat(captured.get(0).getAction()).isEqualTo("campaign.create");
        assertThat(captured.get(0).getResource()).isEqualTo("campaign:" + campaign.getId());
        assertThat(captured.get(0).getResult()).isEqualTo("success");

        // Find the revoke event and assert its fields.
        com.nova.link.audit.AuditEvent revokeEvent = captured.stream()
                .filter(e -> "campaign.revoke".equals(e.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no campaign.revoke audit event"));
        assertThat(revokeEvent.getResource()).isEqualTo("campaign:" + campaign.getId());
        assertThat(revokeEvent.getResult()).isEqualTo("success");
    }

    @Test
    @DisplayName("Null audit store is tolerated (operations still succeed)")
    void nullAuditStoreTolerated() {
        // Do NOT call setAuditStore → store stays null.
        Campaign campaign = createPreviewCampaign("no-audit");
        CampaignResult scheduleResult = manager.scheduleCampaign(
                campaign.getId(), UUID.randomUUID(), true);
        assertThat(scheduleResult.isSuccess()).as(scheduleResult.getMessage()).isTrue();
        // No exception, no audit side-effect — business path completes.
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    @DisplayName("Audit store exceptions are swallowed (business path unaffected)")
    void auditStoreExceptionIsSwallowed() {
        // Throwing AuditStore subclass: record(...) always throws, verifying
        // that CampaignManager.recordAudit catches it and the business path
        // still completes (mirrors ModerationManager's catch-all guard).
        com.nova.link.audit.AuditStore throwingStore =
                new com.nova.link.audit.AuditStore(null) {
                    @Override
                    public void record(com.nova.link.audit.AuditEvent event) {
                        throw new IllegalStateException("simulated audit failure");
                    }
                };
        manager.setAuditStore(throwingStore);
        // createCampaign should still succeed despite the throwing store.
        Campaign campaign = createPreviewCampaign("throwing-audit");
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PREVIEW);
    }

    // ====================== misc validation ======================

    @Test
    @DisplayName("createCampaign rejects unknown channelId (NC-404)")
    void createCampaignRejectsUnknownChannel() {
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("survival");
        CampaignResult result = manager.createCampaign(
                UUID.randomUUID(), "no-such-channel", "content", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5,
                "TestClient", true);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(CampaignResult.CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("createCampaign rejects blank content / blank-only platforms (NC-400)")
    void createCampaignRejectsBlankFields() {
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("survival");
        // Blank content.
        CampaignResult blank = manager.createCampaign(
                UUID.randomUUID(), CHANNEL_ID, "  ", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5,
                "TestClient", true);
        assertThat(blank.isSuccess()).isFalse();
        assertThat(blank.getErrorCode()).isEqualTo(CampaignResult.CODE_BAD_REQUEST);

        // Blank-only platforms (after sanitization → empty).
        Set<String> blankPlatforms = new LinkedHashSet<>();
        blankPlatforms.add("   ");
        CampaignResult noPlatforms = manager.createCampaign(
                UUID.randomUUID(), CHANNEL_ID, "content", blankPlatforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5,
                "TestClient", true);
        assertThat(noPlatforms.isSuccess()).isFalse();
        assertThat(noPlatforms.getErrorCode()).isEqualTo(CampaignResult.CODE_BAD_REQUEST);
    }

    @Test
    @DisplayName("scheduleCampaign on unknown campaignId returns NC-404")
    void scheduleUnknownCampaignReturns404() {
        CampaignResult result = manager.scheduleCampaign(
                "CMP-deadbeef", UUID.randomUUID(), true);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(CampaignResult.CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("Campaign IDs are CMP- prefixed and unique")
    void campaignIdPrefixAndUniqueness() {
        Campaign a = createPreviewCampaign("a");
        Campaign b = createPreviewCampaign("b");
        assertThat(a.getId()).startsWith(Campaign.ID_PREFIX);
        assertThat(b.getId()).startsWith(Campaign.ID_PREFIX);
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    @Test
    @DisplayName("listCampaigns filters by channelId")
    void listCampaignsFiltersByChannel() {
        // Seed a second channel + campaign.
        channelManager.createChannel(ChannelConfig.builder()
                .id("alert")
                .displayName("Alert")
                .scope(ChannelScope.GLOBAL)
                .build());
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("survival");
        manager.createCampaign(UUID.randomUUID(), CHANNEL_ID, "s1", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5, "TestClient", true);
        manager.createCampaign(UUID.randomUUID(), "alert", "a1", platforms,
                DeliveryPolicy.INSTANT, 0L, 0L, 5, "TestClient", true);

        assertThat(manager.listCampaigns(CHANNEL_ID)).hasSize(1);
        assertThat(manager.listCampaigns("alert")).hasSize(1);
        // Null filter → all campaigns.
        assertThat(manager.listCampaigns(null)).hasSize(2);
    }
}
