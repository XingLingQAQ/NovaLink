package com.nova.link.api;

import com.nova.link.announcement.AnnouncementManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.config.ConfigManager;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.websocket.WebSocketGateway;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates lightweight liveness/readiness status and Prometheus exposition
 * metrics for the REST monitoring endpoints (GET /api/health, GET /api/metrics).
 *
 * <p>Satisfies audit §11.7 monitoring gate. Hand-written — no Prometheus client
 * library, no new dependencies.
 *
 * <p>All dependencies are optional (nullable) so the service tolerates partial
 * wiring: {@link AnnouncementManager} is setter-injected on {@link RestApiHandler}
 * and may be null during early startup or in unit tests, and other refs may be
 * absent in minimal test harnesses. Each check degrades gracefully (reports
 * {@code healthy=false} with a zero count) rather than throwing.
 *
 * <p>This class performs only non-blocking snapshot reads: counter getters and
 * map sizes are O(1); {@link DatabaseProvider#isConnected()} is a connection
 * flag, not a round-trip ping. The authenticated-connection count iterates a
 * {@link ServerNetworkHandler#getConnections() snapshot copy} — O(n) in
 * connection count but non-blocking, and well within the 50ms probe budget for
 * realistic connection volumes. No blocking I/O is performed, so /api/health
 * is safe to invoke from the Netty IO thread.
 */
public final class HealthMetricsService {

    /** Backend version reported in /api/health. Mirrors GET /api/status. */
    private static final String VERSION = "1.0.0";

    private final ServerNetworkHandler networkHandler;
    private final ChannelManager channelManager;
    private final WebhookManager webhookManager;
    private final AnnouncementManager announcementManager;
    private final DatabaseProvider databaseProvider;
    private final ConfigManager configManager;
    private final WebSocketGateway webSocketGateway;

    public HealthMetricsService(ServerNetworkHandler networkHandler,
                                ChannelManager channelManager,
                                WebhookManager webhookManager,
                                AnnouncementManager announcementManager,
                                DatabaseProvider databaseProvider) {
        this(networkHandler, channelManager, webhookManager, announcementManager,
                databaseProvider, null, null);
    }

    /**
     * Full constructor (§11.6 Project 17 — batch + observability). The
     * {@code configManager} backs the {@code nova_link_config_revision} metric
     * and {@code webSocketGateway} backs {@code nova_link_ws_sessions_active};
     * both are nullable so this service tolerates partial wiring exactly like
     * the other optional dependencies.
     */
    public HealthMetricsService(ServerNetworkHandler networkHandler,
                                ChannelManager channelManager,
                                WebhookManager webhookManager,
                                AnnouncementManager announcementManager,
                                DatabaseProvider databaseProvider,
                                ConfigManager configManager,
                                WebSocketGateway webSocketGateway) {
        this.networkHandler = networkHandler;
        this.channelManager = channelManager;
        this.webhookManager = webhookManager;
        this.announcementManager = announcementManager;
        this.databaseProvider = databaseProvider;
        this.configManager = configManager;
        this.webSocketGateway = webSocketGateway;
    }

    /**
     * Liveness + readiness snapshot. Does NOT leak secrets, passwords, or
     * webhook URLs — only aggregate counts and boolean health flags.
     *
     * <p>{@code status} logic:
     * <ul>
     *   <li><b>up</b> — every dependency is wired and the database is connected.</li>
     *   <li><b>degraded</b> — an optional dependency is missing/unwired, but the
     *       process is still serving (readiness tolerates partial wiring).</li>
     *   <li><b>down</b> — the database is explicitly disconnected; the backend
     *       cannot fulfill its core persistence contract.</li>
     * </ul>
     *
     * @return an ordered map suitable for JSON serialization
     */
    public Map<String, Object> health() {
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        boolean dbAvailable = databaseProvider != null;
        boolean dbAlive = dbAvailable && databaseProvider.isConnected();

        int physicalConnections = 0;
        int authenticatedConnections = 0;
        if (networkHandler != null) {
            physicalConnections = networkHandler.getConnectionCount();
            authenticatedConnections = countAuthenticated(networkHandler, physicalConnections);
        }

        int channelCount = channelManager != null ? channelManager.getChannelCount() : 0;
        int announcementCount = announcementManager != null
                ? announcementManager.getAllAnnouncements().size() : 0;

        // §11.6 Project 17: queue / ws / config observability snapshots.
        int controlQueueDepth = 0;
        int controlQueueCapacity = 0;
        int messageQueueDepth = 0;
        int messageQueueCapacity = 0;
        long packetsDroppedTotal = 0L;
        if (networkHandler != null) {
            controlQueueDepth = networkHandler.getControlQueueDepth();
            controlQueueCapacity = networkHandler.getControlQueueCapacity();
            messageQueueDepth = networkHandler.getMessageQueueDepth();
            messageQueueCapacity = networkHandler.getMessageQueueCapacity();
            packetsDroppedTotal = networkHandler.getDropCountTotal();
        }
        boolean queuesSaturated = controlQueueDepth >= controlQueueCapacity
                || messageQueueDepth >= messageQueueCapacity;
        int wsSessionsActive = webSocketGateway != null ? webSocketGateway.getSessionCount() : 0;
        long configRevision = configManager != null ? configManager.getSettingsRevision() : 0L;

        String status;
        if (dbAvailable && !dbAlive) {
            status = "down";
        } else if (networkHandler == null || channelManager == null
                || announcementManager == null || !dbAvailable) {
            status = "degraded";
        } else {
            status = "up";
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("status", status);
        root.put("version", VERSION);
        root.put("uptimeMillis", uptimeMillis);
        root.put("timestamp", System.currentTimeMillis());

        Map<String, Object> checks = new LinkedHashMap<>();

        Map<String, Object> connCheck = new LinkedHashMap<>();
        connCheck.put("healthy", networkHandler != null);
        connCheck.put("physical", physicalConnections);
        connCheck.put("authenticated", authenticatedConnections);
        checks.put("connections", connCheck);

        Map<String, Object> chanCheck = new LinkedHashMap<>();
        chanCheck.put("healthy", channelManager != null);
        chanCheck.put("total", channelCount);
        checks.put("channels", chanCheck);

        Map<String, Object> annCheck = new LinkedHashMap<>();
        annCheck.put("healthy", announcementManager != null);
        annCheck.put("total", announcementCount);
        checks.put("announcements", annCheck);

        Map<String, Object> dbCheck = new LinkedHashMap<>();
        dbCheck.put("healthy", dbAlive);
        dbCheck.put("available", dbAvailable);
        checks.put("database", dbCheck);

        // §11.6 Project 17: queues / ws / config sub-checks. Each degrades
        // gracefully (reports healthy=false with zero counts) when its
        // dependency is not wired.
        Map<String, Object> queueCheck = new LinkedHashMap<>();
        queueCheck.put("healthy", !queuesSaturated);
        queueCheck.put("controlQueueDepth", controlQueueDepth);
        queueCheck.put("controlQueueCapacity", controlQueueCapacity);
        queueCheck.put("messageQueueDepth", messageQueueDepth);
        queueCheck.put("messageQueueCapacity", messageQueueCapacity);
        queueCheck.put("packetsDroppedTotal", packetsDroppedTotal);
        checks.put("queues", queueCheck);

        Map<String, Object> wsCheck = new LinkedHashMap<>();
        wsCheck.put("healthy", webSocketGateway != null);
        wsCheck.put("sessionsActive", wsSessionsActive);
        checks.put("ws", wsCheck);

        Map<String, Object> cfgCheck = new LinkedHashMap<>();
        cfgCheck.put("healthy", configManager != null);
        cfgCheck.put("revision", configRevision);
        checks.put("config", cfgCheck);

        root.put("checks", checks);
        return root;
    }

    /**
     * Prometheus exposition-format (text/plain; version=0.0.4) snapshot. Hand
     * written — no client library. Every metric name carries exactly one
     * {@code # HELP} and one {@code # TYPE} line. Webhook delivery counters are
     * emitted only when a {@link WebhookManager} is wired; their public getters
     * back every value (no reflection, no WebhookManager modification).
     */
    public String prometheusMetrics() {
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        int physicalConnections = 0;
        int authenticatedConnections = 0;
        if (networkHandler != null) {
            physicalConnections = networkHandler.getConnectionCount();
            authenticatedConnections = countAuthenticated(networkHandler, physicalConnections);
        }
        int channelCount = channelManager != null ? channelManager.getChannelCount() : 0;
        int announcementCount = announcementManager != null
                ? announcementManager.getAllAnnouncements().size() : 0;
        boolean dbAlive = databaseProvider != null && databaseProvider.isConnected();

        // §11.6 Project 17: queue / drop / ws / config snapshots for the new
        // gauges + counters. Each degrades to 0 when its dependency is absent.
        int controlQueueDepth = 0;
        int controlQueueCapacity = 0;
        int messageQueueDepth = 0;
        int messageQueueCapacity = 0;
        Map<Integer, Long> dropCounts = Map.of();
        long packetsDroppedTotal = 0L;
        if (networkHandler != null) {
            controlQueueDepth = networkHandler.getControlQueueDepth();
            controlQueueCapacity = networkHandler.getControlQueueCapacity();
            messageQueueDepth = networkHandler.getMessageQueueDepth();
            messageQueueCapacity = networkHandler.getMessageQueueCapacity();
            dropCounts = networkHandler.getDropCounts();
            packetsDroppedTotal = networkHandler.getDropCountTotal();
        }
        int wsSessionsActive = webSocketGateway != null ? webSocketGateway.getSessionCount() : 0;
        long configRevision = configManager != null ? configManager.getSettingsRevision() : 0L;

        StringBuilder sb = new StringBuilder(1024);

        sb.append("# HELP nova_link_uptime_seconds JVM uptime since backend start.\n");
        sb.append("# TYPE nova_link_uptime_seconds gauge\n");
        sb.append("nova_link_uptime_seconds ").append(uptimeSeconds).append("\n\n");

        sb.append("# HELP nova_link_connections_active Number of client connections by state.\n");
        sb.append("# TYPE nova_link_connections_active gauge\n");
        sb.append("nova_link_connections_active{state=\"physical\"} ").append(physicalConnections).append("\n");
        sb.append("nova_link_connections_active{state=\"authenticated\"} ").append(authenticatedConnections).append("\n\n");

        sb.append("# HELP nova_link_channels_total Number of channels registered.\n");
        sb.append("# TYPE nova_link_channels_total gauge\n");
        sb.append("nova_link_channels_total ").append(channelCount).append("\n\n");

        sb.append("# HELP nova_link_announcements_active Number of announcements registered.\n");
        sb.append("# TYPE nova_link_announcements_active gauge\n");
        sb.append("nova_link_announcements_active ").append(announcementCount).append("\n\n");

        sb.append("# HELP nova_link_db_alive Database connectivity (1=connected, 0=disconnected).\n");
        sb.append("# TYPE nova_link_db_alive gauge\n");
        sb.append("nova_link_db_alive ").append(dbAlive ? 1 : 0).append("\n\n");

        // §11.6 Project 17: control-plane queue depth + capacity.
        if (networkHandler != null) {
            sb.append("# HELP nova_link_control_queue_depth Queued control-plane tasks awaiting execution.\n");
            sb.append("# TYPE nova_link_control_queue_depth gauge\n");
            sb.append("nova_link_control_queue_depth ").append(controlQueueDepth).append("\n\n");

            sb.append("# HELP nova_link_control_queue_capacity Maximum control-plane queue depth.\n");
            sb.append("# TYPE nova_link_control_queue_capacity gauge\n");
            sb.append("nova_link_control_queue_capacity ").append(controlQueueCapacity).append("\n\n");

            sb.append("# HELP nova_link_message_queue_depth Queued message-plane (chat) tasks awaiting execution.\n");
            sb.append("# TYPE nova_link_message_queue_depth gauge\n");
            sb.append("nova_link_message_queue_depth ").append(messageQueueDepth).append("\n\n");

            sb.append("# HELP nova_link_message_queue_capacity Maximum message-plane queue depth.\n");
            sb.append("# TYPE nova_link_message_queue_capacity gauge\n");
            sb.append("nova_link_message_queue_capacity ").append(messageQueueCapacity).append("\n\n");

            // Per-packet-type drop counters (one series per non-zero packet id)
            // plus an explicit {packet_id="total"} aggregate. Iteration order is
            // stabilized by sorting the keys so the exposition is stable across
            // scrapes (a requirement for meaningful Prometheus dashboards).
            sb.append("# HELP nova_link_packets_dropped_total Packets dropped because the executor was saturated, by packet id.\n");
            sb.append("# TYPE nova_link_packets_dropped_total counter\n");
            List<Integer> sortedPacketIds = new ArrayList<>(dropCounts.keySet());
            Collections.sort(sortedPacketIds);
            for (Integer packetId : sortedPacketIds) {
                sb.append("nova_link_packets_dropped_total{packet_id=\"").append(packetId)
                        .append("\"} ").append(dropCounts.get(packetId)).append("\n");
            }
            sb.append("nova_link_packets_dropped_total{packet_id=\"total\"} ")
                    .append(packetsDroppedTotal).append("\n\n");
        }

        // §11.6 Project 17: active WebSocket sessions.
        if (webSocketGateway != null) {
            sb.append("# HELP nova_link_ws_sessions_active Authenticated web panel sessions.\n");
            sb.append("# TYPE nova_link_ws_sessions_active gauge\n");
            sb.append("nova_link_ws_sessions_active ").append(wsSessionsActive).append("\n\n");
        }

        // §11.6 Project 17: settings revision (PANEL-010).
        if (configManager != null) {
            sb.append("# HELP nova_link_config_revision Monotonic settings revision (PANEL-010).\n");
            sb.append("# TYPE nova_link_config_revision gauge\n");
            sb.append("nova_link_config_revision ").append(configRevision).append("\n\n");
        }

        if (webhookManager != null) {
            sb.append("# HELP nova_link_webhook_deliveries_total Logical webhook deliveries by terminal result.\n");
            sb.append("# TYPE nova_link_webhook_deliveries_total counter\n");
            sb.append("nova_link_webhook_deliveries_total{result=\"accepted\"} ")
                    .append(webhookManager.getAcceptedDeliveryCount()).append("\n");
            sb.append("nova_link_webhook_deliveries_total{result=\"rejected\"} ")
                    .append(webhookManager.getRejectedDeliveryCount()).append("\n");
            sb.append("nova_link_webhook_deliveries_total{result=\"succeeded\"} ")
                    .append(webhookManager.getSuccessfulDeliveryCount()).append("\n");
            sb.append("nova_link_webhook_deliveries_total{result=\"failed\"} ")
                    .append(webhookManager.getFailedDeliveryCount()).append("\n");
            sb.append("nova_link_webhook_deliveries_total{result=\"completed\"} ")
                    .append(webhookManager.getCompletedDeliveryCount()).append("\n\n");

            sb.append("# HELP nova_link_webhook_delivery_queue_depth Webhook delivery worker queue depth.\n");
            sb.append("# TYPE nova_link_webhook_delivery_queue_depth gauge\n");
            sb.append("nova_link_webhook_delivery_queue_depth ")
                    .append(webhookManager.getDeliveryQueueDepth()).append("\n\n");

            sb.append("# HELP nova_link_webhook_delivery_queue_capacity Maximum delivery worker queue depth.\n");
            sb.append("# TYPE nova_link_webhook_delivery_queue_capacity gauge\n");
            sb.append("nova_link_webhook_delivery_queue_capacity ")
                    .append(webhookManager.getDeliveryQueueCapacity()).append("\n\n");

            sb.append("# HELP nova_link_webhook_pending_retries Delayed retries retained by the scheduler.\n");
            sb.append("# TYPE nova_link_webhook_pending_retries gauge\n");
            sb.append("nova_link_webhook_pending_retries ")
                    .append(webhookManager.getPendingRetryCount()).append("\n\n");

            sb.append("# HELP nova_link_webhook_retry_capacity Maximum delayed retries retained.\n");
            sb.append("# TYPE nova_link_webhook_retry_capacity gauge\n");
            sb.append("nova_link_webhook_retry_capacity ")
                    .append(webhookManager.getRetryQueueCapacity()).append("\n\n");

            sb.append("# HELP nova_link_webhook_retries_rejected_total Retries rejected because the backlog was full.\n");
            sb.append("# TYPE nova_link_webhook_retries_rejected_total counter\n");
            sb.append("nova_link_webhook_retries_rejected_total ")
                    .append(webhookManager.getRetryRejectedCount()).append("\n\n");

            sb.append("# HELP nova_link_webhook_attempts_rejected_total Delivery attempts rejected because the worker queue was full.\n");
            sb.append("# TYPE nova_link_webhook_attempts_rejected_total counter\n");
            sb.append("nova_link_webhook_attempts_rejected_total ")
                    .append(webhookManager.getRejectedAttemptCount()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Counts authenticated (active-generation) connections via a non-blocking
     * snapshot read. Falls back to the physical count if introspection throws
     * (defensive — never let a health probe 500).
     */
    private static int countAuthenticated(ServerNetworkHandler handler, int fallback) {
        try {
            Set<ClientConnection> snapshot = handler.getConnections();
            int count = 0;
            for (ClientConnection c : snapshot) {
                if (c != null && c.isAuthenticated()) {
                    count++;
                }
            }
            return count;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
