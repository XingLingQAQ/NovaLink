package com.nova.link.announcement;

import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.notification.NotificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Manages announcements for channels in the NovaLink system.
 * Supports immediate, join-triggered, and scheduled (Cron) announcements.
 * 
 * Requirements:
 * - 14.1: Send announcement to channel via /nc announce command
 * - 14.2: Scheduled announcements via Cron expression
 * - 14.3: Join announcements when player joins channel
 * - 14.4: Channel admin: limited to their private channels
 * - 14.5: Client admin: limited to their client's channels
 * - 14.6: Super admin: any channel and cross-channel broadcast
 */
public class AnnouncementManager {

    private static final Logger logger = LoggerFactory.getLogger(AnnouncementManager.class);

    /** Characters used for generating announcement IDs */
    private static final String ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** Length of announcement IDs */
    private static final int ID_LENGTH = 8;

    /** All announcements indexed by ID */
    private final Map<String, Announcement> announcements = new ConcurrentHashMap<>();

    /** Join announcements indexed by channel ID */
    private final Map<String, Set<String>> joinAnnouncementsByChannel = new ConcurrentHashMap<>();

    /** Scheduled announcements indexed by ID */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    private final PermissionManager permissionManager;
    private final ChannelManager channelManager;
    private final SecureRandom random = new SecureRandom();

    /**
     * Optional notification store so announcement events are persisted and
     * broadcast to the web panel. Injected via setter to keep the constructor
     * signature stable.
     */
    private NotificationStore notificationStore;

    /** Executor for scheduled announcements */
    private ScheduledExecutorService scheduler;

    /** Callback for sending announcements to channels */
    private BiConsumer<String, String> announcementSender;

    public AnnouncementManager(PermissionManager permissionManager, ChannelManager channelManager) {
        this.permissionManager = permissionManager;
        this.channelManager = channelManager;
    }

    /**
     * Sets the optional notification store so announcement events are persisted
     * and broadcast to the web panel.
     */
    public void setNotificationStore(NotificationStore notificationStore) {
        this.notificationStore = notificationStore;
    }

    /**
     * Initializes the announcement manager and starts the scheduler.
     */
    public void initialize() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "AnnouncementManager-Scheduler");
            t.setDaemon(true);
            return t;
        });
        logger.info("AnnouncementManager initialized");
    }

    /**
     * Shuts down the announcement manager.
     */
    public void shutdown() {
        // Cancel all scheduled tasks
        for (ScheduledFuture<?> task : scheduledTasks.values()) {
            task.cancel(false);
        }
        scheduledTasks.clear();

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        announcements.clear();
        joinAnnouncementsByChannel.clear();
        logger.info("AnnouncementManager shutdown");
    }

    /**
     * Sets the callback for sending announcements to channels.
     *
     * @param sender the callback (channelId, content) -> void
     */
    public void setAnnouncementSender(BiConsumer<String, String> sender) {
        this.announcementSender = sender;
    }


    /**
     * Sends an immediate announcement to a channel.
     * 
     * Requirements:
     * - 14.1: Send announcement via /nc announce command
     * - 14.4: Channel admin: limited to their private channels
     * - 14.5: Client admin: limited to their client's channels
     * - 14.6: Super admin: any channel
     *
     * @param operatorId the UUID of the operator
     * @param channelId the channel ID to send to
     * @param content the announcement content
     * @param operatorClientId the client ID of the operator
     * @return the result of the operation
     */
    public AnnouncementResult sendImmediateAnnouncement(UUID operatorId, String channelId,
                                                         String content, String operatorClientId) {
        if (operatorId == null) {
            return AnnouncementResult.badRequest("Operator ID is required");
        }
        if (channelId == null || channelId.isEmpty()) {
            return AnnouncementResult.badRequest("Channel ID is required");
        }
        if (content == null || content.isEmpty()) {
            return AnnouncementResult.badRequest("Content is required");
        }

        // Validate permission and scope
        AnnouncementResult validationResult = validateAnnouncementPermission(
                operatorId, channelId, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Send the announcement
        if (announcementSender != null) {
            announcementSender.accept(channelId, content);
        }

        logger.info("Immediate announcement sent to channel {} by {}: {}",
                channelId, operatorId, truncateContent(content));

        // Surface the announcement to the web panel notification feed.
        if (notificationStore != null) {
            try {
                notificationStore.createNotification(
                        "Announcement",
                        "Announcement sent to channel " + channelId + ": " + truncateContent(content),
                        "info");
            } catch (Exception ignored) {
                // non-fatal
            }
        }

        return AnnouncementResult.success("Announcement sent successfully");
    }

    /**
     * Sends a cross-channel broadcast (super admin only).
     * 
     * Requirements:
     * - 14.6: Super admin: cross-channel broadcast
     *
     * @param operatorId the UUID of the operator
     * @param content the announcement content
     * @param targetChannelIds the list of channel IDs to broadcast to (null for all)
     * @return the result of the operation
     */
    public AnnouncementResult sendBroadcast(UUID operatorId, String content, 
                                             List<String> targetChannelIds) {
        if (operatorId == null) {
            return AnnouncementResult.badRequest("Operator ID is required");
        }
        if (content == null || content.isEmpty()) {
            return AnnouncementResult.badRequest("Content is required");
        }

        // Only super admin can broadcast
        PermissionLevel level = permissionManager.getPermissionLevel(operatorId, null);
        if (level != PermissionLevel.SUPER_ADMIN) {
            return AnnouncementResult.forbidden("Only super admin can send cross-channel broadcasts");
        }

        // Determine target channels
        Collection<String> targets;
        if (targetChannelIds != null && !targetChannelIds.isEmpty()) {
            targets = targetChannelIds;
        } else {
            targets = channelManager.getAllChannels().stream()
                    .map(Channel::getId)
                    .collect(Collectors.toList());
        }

        // Send to all target channels
        int sentCount = 0;
        if (announcementSender != null) {
            for (String channelId : targets) {
                announcementSender.accept(channelId, content);
                sentCount++;
            }
        }

        logger.info("Broadcast sent to {} channels by {}: {}",
                sentCount, operatorId, truncateContent(content));

        // Surface the broadcast to the web panel notification feed.
        if (notificationStore != null) {
            try {
                notificationStore.createNotification(
                        "Announcement",
                        "Broadcast sent to " + sentCount + " channels: " + truncateContent(content),
                        "info");
            } catch (Exception ignored) {
                // non-fatal
            }
        }

        return AnnouncementResult.success("Broadcast sent to " + sentCount + " channels");
    }

    /**
     * Creates a join announcement for a channel.
     * 
     * Requirements:
     * - 14.3: Join announcements when player joins channel
     * - 14.4-14.6: Permission-based scope
     *
     * @param operatorId the UUID of the operator
     * @param channelId the channel ID
     * @param content the announcement content
     * @param operatorClientId the client ID of the operator
     * @return the result of the operation
     */
    public AnnouncementResult createJoinAnnouncement(UUID operatorId, String channelId,
                                                      String content, String operatorClientId) {
        if (operatorId == null) {
            return AnnouncementResult.badRequest("Operator ID is required");
        }
        if (channelId == null || channelId.isEmpty()) {
            return AnnouncementResult.badRequest("Channel ID is required");
        }
        if (content == null || content.isEmpty()) {
            return AnnouncementResult.badRequest("Content is required");
        }

        // Validate permission and scope
        AnnouncementResult validationResult = validateAnnouncementPermission(
                operatorId, channelId, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Create the announcement
        String announcementId = generateAnnouncementId();
        Announcement announcement = new Announcement(
                announcementId, channelId, content, AnnouncementType.JOIN, 
                operatorId, operatorClientId);

        // Store the announcement
        announcements.put(announcementId, announcement);
        joinAnnouncementsByChannel.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet())
                .add(announcementId);

        logger.info("Join announcement created for channel {} by {}: {}", 
                channelId, operatorId, announcementId);

        return AnnouncementResult.success("Join announcement created", announcement);
    }

    /**
     * Creates a scheduled announcement for a channel.
     * 
     * Requirements:
     * - 14.2: Scheduled announcements via Cron expression
     * - 14.4-14.6: Permission-based scope
     *
     * @param operatorId the UUID of the operator
     * @param channelId the channel ID
     * @param content the announcement content
     * @param cronExpression the Cron expression for scheduling
     * @param operatorClientId the client ID of the operator
     * @return the result of the operation
     */
    public AnnouncementResult createScheduledAnnouncement(UUID operatorId, String channelId,
                                                           String content, String cronExpression,
                                                           String operatorClientId) {
        if (operatorId == null) {
            return AnnouncementResult.badRequest("Operator ID is required");
        }
        if (channelId == null || channelId.isEmpty()) {
            return AnnouncementResult.badRequest("Channel ID is required");
        }
        if (content == null || content.isEmpty()) {
            return AnnouncementResult.badRequest("Content is required");
        }
        if (cronExpression == null || cronExpression.isEmpty()) {
            return AnnouncementResult.badRequest("Cron expression is required");
        }

        // Validate permission and scope
        AnnouncementResult validationResult = validateAnnouncementPermission(
                operatorId, channelId, operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Parse and validate cron expression
        CronSchedule schedule;
        try {
            schedule = CronSchedule.parse(cronExpression);
        } catch (IllegalArgumentException e) {
            return AnnouncementResult.badRequest("Invalid cron expression: " + e.getMessage());
        }

        // Create the announcement
        String announcementId = generateAnnouncementId();
        Announcement announcement = new Announcement(
                announcementId, channelId, content, AnnouncementType.SCHEDULED,
                operatorId, operatorClientId);
        announcement.setCronExpression(cronExpression);

        // Store the announcement
        announcements.put(announcementId, announcement);

        // Schedule the task
        scheduleAnnouncement(announcement, schedule);

        logger.info("Scheduled announcement created for channel {} by {}: {} (cron: {})", 
                channelId, operatorId, announcementId, cronExpression);

        return AnnouncementResult.success("Scheduled announcement created", announcement);
    }


    /**
     * Deletes an announcement.
     *
     * @param operatorId the UUID of the operator
     * @param announcementId the announcement ID to delete
     * @param operatorClientId the client ID of the operator
     * @return the result of the operation
     */
    public AnnouncementResult deleteAnnouncement(UUID operatorId, String announcementId,
                                                  String operatorClientId) {
        if (operatorId == null) {
            return AnnouncementResult.badRequest("Operator ID is required");
        }
        if (announcementId == null || announcementId.isEmpty()) {
            return AnnouncementResult.badRequest("Announcement ID is required");
        }

        Announcement announcement = announcements.get(announcementId);
        if (announcement == null) {
            return AnnouncementResult.notFound("Announcement not found: " + announcementId);
        }

        // Validate permission
        AnnouncementResult validationResult = validateAnnouncementPermission(
                operatorId, announcement.getChannelId(), operatorClientId);
        if (!validationResult.isSuccess()) {
            return validationResult;
        }

        // Remove from storage
        announcements.remove(announcementId);

        // Remove from join announcements index
        if (announcement.getType() == AnnouncementType.JOIN) {
            Set<String> channelJoinAnnouncements = joinAnnouncementsByChannel.get(announcement.getChannelId());
            if (channelJoinAnnouncements != null) {
                channelJoinAnnouncements.remove(announcementId);
            }
        }

        // Cancel scheduled task if applicable
        ScheduledFuture<?> task = scheduledTasks.remove(announcementId);
        if (task != null) {
            task.cancel(false);
        }

        logger.info("Announcement deleted: {} by {}", announcementId, operatorId);

        return AnnouncementResult.success("Announcement deleted");
    }

    /**
     * Gets join announcements for a channel.
     * Called when a player joins a channel.
     * 
     * Requirements:
     * - 14.3: Join announcements when player joins channel
     *
     * @param channelId the channel ID
     * @return list of join announcement contents
     */
    public List<String> getJoinAnnouncements(String channelId) {
        if (channelId == null) {
            return Collections.emptyList();
        }

        Set<String> announcementIds = joinAnnouncementsByChannel.get(channelId);
        if (announcementIds == null || announcementIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> contents = new ArrayList<>();
        for (String id : announcementIds) {
            Announcement announcement = announcements.get(id);
            if (announcement != null && announcement.isEnabled()) {
                contents.add(announcement.getContent());
            }
        }
        return contents;
    }

    /**
     * Triggers join announcements for a player joining a channel.
     *
     * @param channelId the channel ID
     * @param playerId the player UUID
     * @param playerName the player name (for placeholder replacement)
     */
    public void triggerJoinAnnouncements(String channelId, UUID playerId, String playerName) {
        List<String> contents = getJoinAnnouncements(channelId);
        if (contents.isEmpty() || announcementSender == null) {
            return;
        }

        for (String content : contents) {
            // Replace player placeholder if present
            String processedContent = content
                    .replace("{player}", playerName != null ? playerName : "Unknown")
                    .replace("{player_uuid}", playerId != null ? playerId.toString() : "");
            announcementSender.accept(channelId, processedContent);
        }

        logger.debug("Triggered {} join announcements for player {} in channel {}", 
                contents.size(), playerName, channelId);
    }

    /**
     * Gets an announcement by ID.
     *
     * @param announcementId the announcement ID
     * @return the announcement, or null if not found
     */
    public Announcement getAnnouncement(String announcementId) {
        return announcements.get(announcementId);
    }

    /**
     * Gets all announcements for a channel.
     *
     * @param channelId the channel ID
     * @return list of announcements
     */
    public List<Announcement> getAnnouncementsByChannel(String channelId) {
        if (channelId == null) {
            return Collections.emptyList();
        }
        return announcements.values().stream()
                .filter(a -> Objects.equals(a.getChannelId(), channelId))
                .collect(Collectors.toList());
    }

    /**
     * Gets all announcements.
     *
     * @return unmodifiable collection of all announcements
     */
    public Collection<Announcement> getAllAnnouncements() {
        return Collections.unmodifiableCollection(announcements.values());
    }

    /**
     * Validates if the operator has permission to create/manage announcements in the specified scope.
     * 
     * Requirements:
     * - 14.4: Channel admin: limited to their private channels
     * - 14.5: Client admin: limited to their client's channels
     * - 14.6: Super admin: any channel
     */
    private AnnouncementResult validateAnnouncementPermission(UUID operatorId, String channelId,
                                                               String operatorClientId) {
        PermissionLevel operatorLevel = permissionManager.getPermissionLevel(operatorId, channelId);

        switch (operatorLevel) {
            case SUPER_ADMIN:
                // Super admin can manage announcements in any channel
                return AnnouncementResult.success("Validated");

            case CLIENT_ADMIN:
                // Client admin can manage announcements in their client's channels
                if (channelId != null) {
                    Channel channel = channelManager.getChannel(channelId);
                    if (channel == null) {
                        return AnnouncementResult.notFound("Channel not found: " + channelId);
                    }
                    // Check if channel belongs to operator's client
                    if (channel.getScope() != ChannelScope.GLOBAL &&
                        !Objects.equals(channel.getClientId(), operatorClientId)) {
                        return AnnouncementResult.forbidden(
                                "Client admin can only manage announcements in their own client's channels");
                    }
                }
                return AnnouncementResult.success("Validated");

            case CHANNEL_ADMIN:
                // Channel admin can only manage announcements in their own channels
                if (channelId == null) {
                    return AnnouncementResult.forbidden(
                            "Channel admin cannot create cross-channel announcements");
                }
                if (!permissionManager.isChannelAdmin(channelId, operatorId)) {
                    return AnnouncementResult.forbidden(
                            "Channel admin can only manage announcements in channels they manage");
                }
                // Additional check: channel admin can only manage private channels
                Channel channel = channelManager.getChannel(channelId);
                if (channel != null && channel.getScope() != ChannelScope.PRIVATE) {
                    return AnnouncementResult.forbidden(
                            "Channel admin can only manage announcements in private channels");
                }
                return AnnouncementResult.success("Validated");

            case PLAYER:
            default:
                return AnnouncementResult.forbidden("Insufficient permissions to manage announcements");
        }
    }

    /**
     * Schedules an announcement based on its cron expression.
     */
    private void scheduleAnnouncement(Announcement announcement, CronSchedule schedule) {
        if (scheduler == null || scheduler.isShutdown()) {
            logger.warn("Scheduler not available, cannot schedule announcement: {}", announcement.getId());
            return;
        }

        long delayMs = schedule.getNextExecutionDelay();
        if (delayMs < 0) {
            logger.warn("Invalid cron schedule for announcement: {}", announcement.getId());
            return;
        }

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (announcement.isEnabled() && announcementSender != null) {
                announcementSender.accept(announcement.getChannelId(), announcement.getContent());
                logger.debug("Scheduled announcement sent: {} to channel {}", 
                        announcement.getId(), announcement.getChannelId());
            }
        }, delayMs, schedule.getPeriodMs(), TimeUnit.MILLISECONDS);

        scheduledTasks.put(announcement.getId(), task);
    }

    /**
     * Generates a unique announcement ID.
     */
    private String generateAnnouncementId() {
        String id;
        int attempts = 0;
        do {
            StringBuilder sb = new StringBuilder("ANN-");
            for (int i = 0; i < ID_LENGTH; i++) {
                sb.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
            }
            id = sb.toString();
            attempts++;
            if (attempts > 1000) {
                throw new IllegalStateException("Unable to generate unique announcement ID");
            }
        } while (announcements.containsKey(id));
        return id;
    }

    /**
     * Truncates content for logging.
     */
    private String truncateContent(String content) {
        if (content == null) return "";
        return content.length() > 50 ? content.substring(0, 50) + "..." : content;
    }

    /**
     * Gets the number of announcements (for testing).
     */
    public int getAnnouncementCount() {
        return announcements.size();
    }

    /**
     * Gets the number of scheduled tasks (for testing).
     */
    public int getScheduledTaskCount() {
        return scheduledTasks.size();
    }

    /**
     * Clears all announcements (for testing).
     */
    public void clear() {
        for (ScheduledFuture<?> task : scheduledTasks.values()) {
            task.cancel(false);
        }
        scheduledTasks.clear();
        announcements.clear();
        joinAnnouncementsByChannel.clear();
    }
}
