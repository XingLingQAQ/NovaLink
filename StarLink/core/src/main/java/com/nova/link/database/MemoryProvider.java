package com.nova.link.database;

import com.nova.link.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of DatabaseProvider.
 * Data is lost when the server restarts.
 * 
 * Requirements: 22.3 - Support no-database mode
 */
public class MemoryProvider implements DatabaseProvider {

    private static final Logger logger = LoggerFactory.getLogger(MemoryProvider.class);

    /** Bounded message history capacity — oldest entries are evicted beyond this. */
    static final int MAX_MESSAGES = 100_000;

    private final Map<UUID, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, MuteInfo>> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, BanInfo>> bans = new ConcurrentHashMap<>();
    private final List<Notification> notifications = Collections.synchronizedList(new ArrayList<>());
    private final List<com.nova.link.audit.AuditEvent> auditEvents = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Invitation> invitations = new ConcurrentHashMap<>();
    private final Deque<ChatMessageRecord> messages = new ArrayDeque<>();
    private final Map<String, com.nova.link.announcement.Announcement> announcements = new ConcurrentHashMap<>();
    private final Map<String, com.nova.link.api.Webhook> persistedWebhooks = new ConcurrentHashMap<>();
    private final Map<String, com.nova.link.announcement.Campaign> campaigns = new ConcurrentHashMap<>();
    private long messageIdSeq = 0;

    private volatile boolean connected = false;

    @Override
    public void initialize() throws DatabaseException {
        connected = true;
        logger.info("MemoryProvider initialized - data will not persist across restarts");
    }

    @Override
    public void shutdown() {
        connected = false;
        playerStates.clear();
        channels.clear();
        mutes.clear();
        bans.clear();
        notifications.clear();
        auditEvents.clear();
        invitations.clear();
        synchronized (messages) {
            messages.clear();
        }
        announcements.clear();
        persistedWebhooks.clear();
        campaigns.clear();
        moderationCases.clear();
        caseEvidence.clear();
        appeals.clear();
        socialRelationsBySource.clear();
        notificationPreferences.clear();
        logger.info("MemoryProvider shutdown - all data cleared");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    // ==================== Player State Operations ====================

    @Override
    public void savePlayerState(PlayerState state) throws DatabaseException {
        checkConnection();
        if (state == null || state.getPlayerId() == null) {
            throw new DatabaseException("Player state and player ID cannot be null");
        }
        // Store a copy to prevent external modifications
        playerStates.put(state.getPlayerId(), new PlayerState(state));
        logger.debug("Saved player state for: {}", state.getPlayerId());
    }

    @Override
    public Optional<PlayerState> loadPlayerState(UUID playerId) throws DatabaseException {
        checkConnection();
        if (playerId == null) {
            return Optional.empty();
        }
        PlayerState state = playerStates.get(playerId);
        // Return a copy to prevent external modifications
        return state != null ? Optional.of(new PlayerState(state)) : Optional.empty();
    }

    @Override
    public void deletePlayerState(UUID playerId) throws DatabaseException {
        checkConnection();
        if (playerId != null) {
            playerStates.remove(playerId);
            mutes.remove(playerId);
            bans.remove(playerId);
            logger.debug("Deleted player state for: {}", playerId);
        }
    }

    @Override
    public List<PlayerState> getAllPlayerStates() throws DatabaseException {
        checkConnection();
        return playerStates.values().stream()
                .map(PlayerState::new)
                .collect(Collectors.toList());
    }

    // ==================== Channel Operations ====================

    @Override
    public void saveChannel(Channel channel) throws DatabaseException {
        checkConnection();
        if (channel == null || channel.getId() == null) {
            throw new DatabaseException("Channel and channel ID cannot be null");
        }
        // Store a defensive copy to prevent external modifications (mirrors savePlayerState)
        channels.put(channel.getId(), new Channel(channel));
        logger.debug("Saved channel: {}", channel.getId());
    }

    @Override
    public Optional<Channel> loadChannel(String channelId) throws DatabaseException {
        checkConnection();
        if (channelId == null) {
            return Optional.empty();
        }
        Channel channel = channels.get(channelId);
        // Return a copy to prevent external modifications
        return channel != null ? Optional.of(new Channel(channel)) : Optional.empty();
    }

    @Override
    public void deleteChannel(String channelId) throws DatabaseException {
        checkConnection();
        if (channelId != null) {
            channels.remove(channelId);
            logger.debug("Deleted channel: {}", channelId);
        }
    }

    @Override
    public List<Channel> getAllChannels() throws DatabaseException {
        checkConnection();
        return channels.values().stream()
                .map(Channel::new)
                .collect(Collectors.toList());
    }

    // ==================== Mute Operations ====================

    @Override
    public void saveMute(UUID playerId, MuteInfo muteInfo) throws DatabaseException {
        checkConnection();
        if (playerId == null || muteInfo == null) {
            throw new DatabaseException("Player ID and mute info cannot be null");
        }
        mutes.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(muteInfo.getChannelId() != null ? muteInfo.getChannelId() : "__global__", muteInfo);
        logger.debug("Saved mute for player {} in channel {}", playerId, muteInfo.getChannelId());
    }

    @Override
    public List<MuteInfo> loadMutes(UUID playerId) throws DatabaseException {
        checkConnection();
        if (playerId == null) {
            return Collections.emptyList();
        }
        Map<String, MuteInfo> playerMutes = mutes.get(playerId);
        if (playerMutes == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(playerMutes.values());
    }

    @Override
    public void deleteMute(UUID playerId, String channelId) throws DatabaseException {
        checkConnection();
        if (playerId == null) {
            return;
        }
        Map<String, MuteInfo> playerMutes = mutes.get(playerId);
        if (playerMutes != null) {
            playerMutes.remove(channelId != null ? channelId : "__global__");
            logger.debug("Deleted mute for player {} in channel {}", playerId, channelId);
        }
    }

    @Override
    public int cleanupExpiredMutes() throws DatabaseException {
        checkConnection();
        int count = 0;
        long now = System.currentTimeMillis();
        for (Map<String, MuteInfo> playerMutes : mutes.values()) {
            Iterator<MuteInfo> iterator = playerMutes.values().iterator();
            while (iterator.hasNext()) {
                MuteInfo mute = iterator.next();
                if (mute.getExpireTime() > 0 && now > mute.getExpireTime()) {
                    iterator.remove();
                    count++;
                }
            }
        }
        if (count > 0) {
            logger.debug("Cleaned up {} expired mutes", count);
        }
        return count;
    }

    @Override
    public Map<UUID, List<MuteInfo>> getAllActiveMutes() throws DatabaseException {
        checkConnection();
        Map<UUID, List<MuteInfo>> result = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, MuteInfo>> entry : mutes.entrySet()) {
            for (MuteInfo mute : entry.getValue().values()) {
                if (mute.getExpireTime() <= 0 || mute.getExpireTime() >= now) {
                    result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(mute);
                }
            }
        }
        return result;
    }

    // ==================== Ban Operations ====================

    @Override
    public void saveBan(UUID playerId, BanInfo banInfo) throws DatabaseException {
        checkConnection();
        if (playerId == null || banInfo == null) {
            throw new DatabaseException("Player ID and ban info cannot be null");
        }
        bans.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(banInfo.getChannelId() != null ? banInfo.getChannelId() : "__global__", banInfo);
        logger.debug("Saved ban for player {} in channel {}", playerId, banInfo.getChannelId());
    }

    @Override
    public List<BanInfo> loadBans(UUID playerId) throws DatabaseException {
        checkConnection();
        if (playerId == null) {
            return Collections.emptyList();
        }
        Map<String, BanInfo> playerBans = bans.get(playerId);
        if (playerBans == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(playerBans.values());
    }

    @Override
    public void deleteBan(UUID playerId, String channelId) throws DatabaseException {
        checkConnection();
        if (playerId == null) {
            return;
        }
        Map<String, BanInfo> playerBans = bans.get(playerId);
        if (playerBans != null) {
            playerBans.remove(channelId != null ? channelId : "__global__");
            logger.debug("Deleted ban for player {} in channel {}", playerId, channelId);
        }
    }

    @Override
    public int cleanupExpiredBans() throws DatabaseException {
        checkConnection();
        int count = 0;
        long now = System.currentTimeMillis();
        for (Map<String, BanInfo> playerBans : bans.values()) {
            Iterator<BanInfo> iterator = playerBans.values().iterator();
            while (iterator.hasNext()) {
                BanInfo ban = iterator.next();
                if (ban.getExpireTime() > 0 && now > ban.getExpireTime()) {
                    iterator.remove();
                    count++;
                }
            }
        }
        if (count > 0) {
            logger.debug("Cleaned up {} expired bans", count);
        }
        return count;
    }

    @Override
    public Map<UUID, List<BanInfo>> getAllActiveBans() throws DatabaseException {
        checkConnection();
        Map<UUID, List<BanInfo>> result = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, BanInfo>> entry : bans.entrySet()) {
            for (BanInfo ban : entry.getValue().values()) {
                if (ban.getExpireTime() <= 0 || ban.getExpireTime() >= now) {
                    result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(ban);
                }
            }
        }
        return result;
    }

    // ==================== Notification Operations ====================

    private long notificationIdSeq = 0;

    @Override
    public void saveNotification(Notification notification) throws DatabaseException {
        checkConnection();
        if (notification == null) {
            throw new DatabaseException("Notification cannot be null");
        }
        synchronized (notifications) {
            long id = ++notificationIdSeq;
            try {
                java.lang.reflect.Field f = Notification.class.getDeclaredField("id");
                f.setAccessible(true);
                f.setLong(notification, id);
            } catch (ReflectiveOperationException e) {
                logger.debug("Could not stamp notification id: {}", e.getMessage());
            }
            notifications.add(notification);
        }
        logger.debug("Saved notification: {}", notification.getTitle());
    }

    @Override
    public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly) throws DatabaseException {
        checkConnection();
        List<Notification> result = new ArrayList<>();
        synchronized (notifications) {
            // Build a descending-by-createdAt view.
            List<Notification> sorted = new ArrayList<>(notifications);
            sorted.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
            int start = Math.max(0, offset);
            int effectiveLimit = Math.max(0, limit);
            if (unreadOnly) {
                // When unreadOnly is set we cannot simply subList before filtering,
                // so collect unread from the full descending list, then apply
                // offset/limit to the filtered set.
                int skipped = 0;
                int collected = 0;
                for (Notification n : sorted) {
                    if (n.isRead()) {
                        continue;
                    }
                    if (skipped < start) {
                        skipped++;
                        continue;
                    }
                    if (collected >= effectiveLimit) {
                        break;
                    }
                    result.add(n);
                    collected++;
                }
            } else {
                int end = Math.min(sorted.size(), start + effectiveLimit);
                for (Notification n : sorted.subList(start, end)) {
                    result.add(n);
                }
            }
        }
        return result;
    }

    @Override
    public void markNotificationRead(long id) throws DatabaseException {
        checkConnection();
        synchronized (notifications) {
            for (Notification n : notifications) {
                if (n.getId() == id) {
                    n.setRead(true);
                    logger.debug("Marked notification {} as read", id);
                    return;
                }
            }
        }
    }

    @Override
    public void markAllNotificationsRead() throws DatabaseException {
        checkConnection();
        int count = 0;
        synchronized (notifications) {
            for (Notification n : notifications) {
                if (!n.isRead()) {
                    n.setRead(true);
                    count++;
                }
            }
        }
        if (count > 0) {
            logger.debug("Marked {} notifications as read", count);
        }
    }

    @Override
    public int clearNotifications() throws DatabaseException {
        checkConnection();
        int count;
        synchronized (notifications) {
            count = notifications.size();
            notifications.clear();
        }
        if (count > 0) {
            logger.debug("Cleared {} notifications", count);
        }
        return count;
    }

    @Override
    public int getUnreadCount() throws DatabaseException {
        checkConnection();
        int count = 0;
        synchronized (notifications) {
            for (Notification n : notifications) {
                if (!n.isRead()) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public int countNotifications(boolean unreadOnly) throws DatabaseException {
        checkConnection();
        if (unreadOnly) {
            return getUnreadCount();
        }
        return notifications.size();
    }

    // --- Per-user notification state (PANEL-014) ---
    // Model: notifications is the immutable event stream (recipient null =
    // broadcast, non-null = directed). notificationReadState stores per-user
    // read flags keyed by (notificationId, userId). A notification is "read"
    // for a user if there is a notificationReadState row with read=true; a
    // directed notification is also considered read if the legacy global
    // Notification.read flag is true (migration-period double-read fallback).

    private final java.util.Map<Long, java.util.Map<String, Boolean>> notificationReadState =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    private boolean isNotificationReadForUser(Notification n, String userId) {
        if (n.isRead()) {
            return true; // legacy global flag fallback (double-read)
        }
        java.util.Map<String, Boolean> users = notificationReadState.get(n.getId());
        return users != null && Boolean.TRUE.equals(users.get(userId));
    }

    private boolean isNotificationVisibleToUser(Notification n, String userId) {
        String recipient = n.getRecipient();
        return recipient == null || recipient.equals(userId);
    }

    @Override
    public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly, String userId)
            throws DatabaseException {
        checkConnection();
        if (userId == null) {
            return getNotifications(offset, limit, unreadOnly);
        }
        List<Notification> result = new ArrayList<>();
        synchronized (notifications) {
            List<Notification> sorted = new ArrayList<>(notifications);
            sorted.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
            int start = Math.max(0, offset);
            int effectiveLimit = Math.max(0, limit);
            if (unreadOnly) {
                int skipped = 0;
                int collected = 0;
                for (Notification n : sorted) {
                    if (!isNotificationVisibleToUser(n, userId)) {
                        continue;
                    }
                    if (isNotificationReadForUser(n, userId)) {
                        continue;
                    }
                    if (skipped < start) {
                        skipped++;
                        continue;
                    }
                    if (collected >= effectiveLimit) {
                        break;
                    }
                    result.add(n);
                    collected++;
                }
            } else {
                int skipped = 0;
                int collected = 0;
                for (Notification n : sorted) {
                    if (!isNotificationVisibleToUser(n, userId)) {
                        continue;
                    }
                    if (skipped < start) {
                        skipped++;
                        continue;
                    }
                    if (collected >= effectiveLimit) {
                        break;
                    }
                    result.add(n);
                    collected++;
                }
            }
        }
        return result;
    }

    @Override
    public void markNotificationRead(long id, String userId) throws DatabaseException {
        checkConnection();
        if (userId == null) {
            markNotificationRead(id);
            return;
        }
        synchronized (notifications) {
            for (Notification n : notifications) {
                if (n.getId() == id) {
                    notificationReadState
                            .computeIfAbsent(id, k -> java.util.Collections.synchronizedMap(new java.util.HashMap<>()))
                            .put(userId, Boolean.TRUE);
                    logger.debug("Marked notification {} as read for user {}", id, userId);
                    return;
                }
            }
        }
    }

    @Override
    public void markAllNotificationsRead(String userId) throws DatabaseException {
        checkConnection();
        if (userId == null) {
            markAllNotificationsRead();
            return;
        }
        int count = 0;
        synchronized (notifications) {
            for (Notification n : notifications) {
                if (!isNotificationVisibleToUser(n, userId)) {
                    continue;
                }
                if (isNotificationReadForUser(n, userId)) {
                    continue;
                }
                notificationReadState
                        .computeIfAbsent(n.getId(), k -> java.util.Collections.synchronizedMap(new java.util.HashMap<>()))
                        .put(userId, Boolean.TRUE);
                count++;
            }
        }
        if (count > 0) {
            logger.debug("Marked {} notifications as read for user {}", count, userId);
        }
    }

    @Override
    public int getUnreadCount(String userId) throws DatabaseException {
        checkConnection();
        if (userId == null) {
            return getUnreadCount();
        }
        int count = 0;
        synchronized (notifications) {
            for (Notification n : notifications) {
                if (!isNotificationVisibleToUser(n, userId)) {
                    continue;
                }
                if (!isNotificationReadForUser(n, userId)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public int countNotifications(boolean unreadOnly, String userId) throws DatabaseException {
        checkConnection();
        if (userId == null) {
            return countNotifications(unreadOnly);
        }
        int count = 0;
        synchronized (notifications) {
            for (Notification n : notifications) {
                if (!isNotificationVisibleToUser(n, userId)) {
                    continue;
                }
                if (unreadOnly && isNotificationReadForUser(n, userId)) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    @Override
    public int clearNotifications(String userId) throws DatabaseException {
        checkConnection();
        if (userId == null) {
            return clearNotifications();
        }
        int count = 0;
        synchronized (notifications) {
            java.util.Iterator<Notification> it = notifications.iterator();
            while (it.hasNext()) {
                Notification n = it.next();
                if (userId.equals(n.getRecipient())) {
                    it.remove();
                    notificationReadState.remove(n.getId());
                    count++;
                }
            }
        }
        if (count > 0) {
            logger.debug("Cleared {} directed notifications for user {}", count, userId);
        }
        return count;
    }

    @Override
    public int clearBroadcastNotifications() throws DatabaseException {
        checkConnection();
        int count = 0;
        synchronized (notifications) {
            java.util.Iterator<Notification> it = notifications.iterator();
            while (it.hasNext()) {
                Notification n = it.next();
                if (n.getRecipient() == null) {
                    it.remove();
                    notificationReadState.remove(n.getId());
                    count++;
                }
            }
        }
        if (count > 0) {
            logger.debug("Cleared {} broadcast notifications", count);
        }
        return count;
    }

    // ==================== Audit Operations ====================

    private long auditIdSeq = 0;

    @Override
    public void saveAuditEvent(com.nova.link.audit.AuditEvent event) throws DatabaseException {
        checkConnection();
        if (event == null) {
            throw new DatabaseException("AuditEvent cannot be null");
        }
        synchronized (auditEvents) {
            long id = ++auditIdSeq;
            try {
                java.lang.reflect.Field f = com.nova.link.audit.AuditEvent.class.getDeclaredField("id");
                f.setAccessible(true);
                f.setLong(event, id);
            } catch (ReflectiveOperationException e) {
                logger.debug("Could not stamp audit event id: {}", e.getMessage());
            }
            auditEvents.add(event);
        }
        logger.debug("Saved audit event: {}", event.getAction());
    }

    @Override
    public List<com.nova.link.audit.AuditEvent> getAuditEvents(int offset, int limit, String actor, String action) throws DatabaseException {
        checkConnection();
        List<com.nova.link.audit.AuditEvent> result = new ArrayList<>();
        synchronized (auditEvents) {
            // Build a descending-by-createdAt view.
            List<com.nova.link.audit.AuditEvent> sorted = new ArrayList<>(auditEvents);
            sorted.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
            int start = Math.max(0, offset);
            int effectiveLimit = Math.max(0, limit);
            boolean filterActor = actor != null && !actor.isEmpty();
            boolean filterAction = action != null && !action.isEmpty();
            if (filterActor || filterAction) {
                // Collect matching rows from the full descending list, then
                // apply offset/limit to the filtered set.
                int skipped = 0;
                int collected = 0;
                for (com.nova.link.audit.AuditEvent e : sorted) {
                    if (filterActor && !actor.equals(e.getActor())) {
                        continue;
                    }
                    if (filterAction && !action.equals(e.getAction())) {
                        continue;
                    }
                    if (skipped < start) {
                        skipped++;
                        continue;
                    }
                    if (collected >= effectiveLimit) {
                        break;
                    }
                    result.add(e);
                    collected++;
                }
            } else {
                int end = Math.min(sorted.size(), start + effectiveLimit);
                for (com.nova.link.audit.AuditEvent e : sorted.subList(start, end)) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    @Override
    public int countAuditEvents(String actor, String action) throws DatabaseException {
        checkConnection();
        boolean filterActor = actor != null && !actor.isEmpty();
        boolean filterAction = action != null && !action.isEmpty();
        if (!filterActor && !filterAction) {
            return auditEvents.size();
        }
        int count = 0;
        synchronized (auditEvents) {
            for (com.nova.link.audit.AuditEvent e : auditEvents) {
                if (filterActor && !actor.equals(e.getActor())) {
                    continue;
                }
                if (filterAction && !action.equals(e.getAction())) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    // ==================== Moderation Operations (schema v11) ====================
    // PANEL-007: moderation case/appeal workflow. Cases are keyed by their UUID id
    // (assigned by the caller, not a sequence) so transitions can upsert in place.
    // Evidence uses a database-style long id stamped by reflection, mirroring the
    // audit-event and notification id-stamping pattern.

    private final Map<String, com.nova.link.moderation.ModerationCase> moderationCases = new ConcurrentHashMap<>();
    private final List<com.nova.link.moderation.CaseEvidence> caseEvidence = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, com.nova.link.moderation.Appeal> appeals = new ConcurrentHashMap<>();
    private long caseEvidenceIdSeq = 0;

    @Override
    public void saveModerationCase(com.nova.link.moderation.ModerationCase moderationCase) throws DatabaseException {
        checkConnection();
        if (moderationCase == null || moderationCase.getId() == null) {
            throw new DatabaseException("ModerationCase and id cannot be null");
        }
        moderationCases.put(moderationCase.getId(), moderationCase);
        logger.debug("Saved moderation case: {}", moderationCase.getId());
    }

    @Override
    public Optional<com.nova.link.moderation.ModerationCase> getModerationCase(String caseId) throws DatabaseException {
        checkConnection();
        if (caseId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(moderationCases.get(caseId));
    }

    @Override
    public List<com.nova.link.moderation.ModerationCase> listModerationCases(int offset, int limit, String status) throws DatabaseException {
        checkConnection();
        List<com.nova.link.moderation.ModerationCase> sorted = new ArrayList<>(moderationCases.values());
        sorted.sort((a, b) -> {
            int byCreated = Long.compare(b.getCreatedAt(), a.getCreatedAt());
            return byCreated != 0 ? byCreated : b.getId().compareTo(a.getId());
        });
        boolean filterStatus = status != null && !status.isEmpty();
        List<com.nova.link.moderation.ModerationCase> result = new ArrayList<>();
        int start = Math.max(0, offset);
        int effectiveLimit = Math.max(0, limit);
        if (filterStatus) {
            int skipped = 0;
            int collected = 0;
            for (com.nova.link.moderation.ModerationCase c : sorted) {
                if (!status.equals(c.getStatus().name())) {
                    continue;
                }
                if (skipped < start) {
                    skipped++;
                    continue;
                }
                if (collected >= effectiveLimit) {
                    break;
                }
                result.add(c);
                collected++;
            }
        } else {
            int end = Math.min(sorted.size(), start + effectiveLimit);
            for (com.nova.link.moderation.ModerationCase c : sorted.subList(start, end)) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public int countModerationCases(String status) throws DatabaseException {
        checkConnection();
        boolean filterStatus = status != null && !status.isEmpty();
        if (!filterStatus) {
            return moderationCases.size();
        }
        int count = 0;
        for (com.nova.link.moderation.ModerationCase c : moderationCases.values()) {
            if (status.equals(c.getStatus().name())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void saveCaseEvidence(com.nova.link.moderation.CaseEvidence evidence) throws DatabaseException {
        checkConnection();
        if (evidence == null || evidence.getCaseId() == null) {
            throw new DatabaseException("CaseEvidence and caseId cannot be null");
        }
        synchronized (caseEvidence) {
            long id = ++caseEvidenceIdSeq;
            try {
                java.lang.reflect.Field f = com.nova.link.moderation.CaseEvidence.class.getDeclaredField("id");
                f.setAccessible(true);
                f.setLong(evidence, id);
            } catch (ReflectiveOperationException e) {
                logger.debug("Could not stamp case evidence id: {}", e.getMessage());
            }
            caseEvidence.add(evidence);
        }
        logger.debug("Saved case evidence for case: {}", evidence.getCaseId());
    }

    @Override
    public List<com.nova.link.moderation.CaseEvidence> listCaseEvidence(String caseId) throws DatabaseException {
        checkConnection();
        if (caseId == null) {
            return Collections.emptyList();
        }
        List<com.nova.link.moderation.CaseEvidence> result = new ArrayList<>();
        synchronized (caseEvidence) {
            for (com.nova.link.moderation.CaseEvidence e : caseEvidence) {
                if (caseId.equals(e.getCaseId())) {
                    result.add(e);
                }
            }
        }
        result.sort(Comparator.comparingLong(com.nova.link.moderation.CaseEvidence::getCreatedAt));
        return result;
    }

    @Override
    public void saveAppeal(com.nova.link.moderation.Appeal appeal) throws DatabaseException {
        checkConnection();
        if (appeal == null || appeal.getId() == null) {
            throw new DatabaseException("Appeal and id cannot be null");
        }
        appeals.put(appeal.getId(), appeal);
        logger.debug("Saved appeal: {}", appeal.getId());
    }

    @Override
    public Optional<com.nova.link.moderation.Appeal> getAppeal(String appealId) throws DatabaseException {
        checkConnection();
        if (appealId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(appeals.get(appealId));
    }

    @Override
    public List<com.nova.link.moderation.Appeal> listAppeals(int offset, int limit, String status) throws DatabaseException {
        checkConnection();
        List<com.nova.link.moderation.Appeal> sorted = new ArrayList<>(appeals.values());
        sorted.sort((a, b) -> {
            int byCreated = Long.compare(b.getCreatedAt(), a.getCreatedAt());
            return byCreated != 0 ? byCreated : b.getId().compareTo(a.getId());
        });
        boolean filterStatus = status != null && !status.isEmpty();
        List<com.nova.link.moderation.Appeal> result = new ArrayList<>();
        int start = Math.max(0, offset);
        int effectiveLimit = Math.max(0, limit);
        if (filterStatus) {
            int skipped = 0;
            int collected = 0;
            for (com.nova.link.moderation.Appeal a : sorted) {
                if (!status.equals(a.getStatus().name())) {
                    continue;
                }
                if (skipped < start) {
                    skipped++;
                    continue;
                }
                if (collected >= effectiveLimit) {
                    break;
                }
                result.add(a);
                collected++;
            }
        } else {
            int end = Math.min(sorted.size(), start + effectiveLimit);
            for (com.nova.link.moderation.Appeal a : sorted.subList(start, end)) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public int countAppeals(String status) throws DatabaseException {
        checkConnection();
        boolean filterStatus = status != null && !status.isEmpty();
        if (!filterStatus) {
            return appeals.size();
        }
        int count = 0;
        for (com.nova.link.moderation.Appeal a : appeals.values()) {
            if (status.equals(a.getStatus().name())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void updateAppealReview(String appealId, com.nova.link.moderation.AppealStatus status,
                                   String reviewedBy, String reviewNote, long reviewedAt)
            throws DatabaseException {
        checkConnection();
        if (appealId == null) {
            throw new DatabaseException("Appeal id cannot be null");
        }
        com.nova.link.moderation.Appeal existing = appeals.get(appealId);
        if (existing == null) {
            throw new DatabaseException("Appeal not found: " + appealId);
        }
        com.nova.link.moderation.Appeal updated = new com.nova.link.moderation.Appeal(
                existing.getId(), existing.getCaseId(), existing.getAppellant(),
                existing.getAppealReason(), status, reviewedBy, reviewNote, reviewedAt,
                existing.getContentHash(), existing.getCreatedAt());
        appeals.put(appealId, updated);
        logger.debug("Updated appeal {} review status={}", appealId, status);
    }

    // ==================== Invitation Operations ====================

    @Override
    public void saveInvitation(Invitation invitation) throws DatabaseException {
        checkConnection();
        if (invitation == null || invitation.getCode() == null) {
            throw new DatabaseException("Invitation and code cannot be null");
        }
        invitations.put(invitation.getCode(), invitation);
        logger.debug("Saved invitation: {}", invitation.getCode());
    }

    @Override
    public Optional<Invitation> loadInvitation(String code) throws DatabaseException {
        checkConnection();
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(invitations.get(code));
    }

    @Override
    public boolean markInvitationUsed(String code, UUID usedBy) throws DatabaseException {
        checkConnection();
        Invitation invitation = invitations.get(code);
        if (invitation == null) {
            return false;
        }
        // Atomic check-and-mark: only the caller that observes used=false may
        // flip it, mirroring the SQL `AND used = FALSE` guard. Without this,
        // two concurrent accepts could both see used=false and both proceed.
        synchronized (invitation) {
            if (invitation.isUsed()) {
                logger.debug("Invitation {} already marked used; skipping", code);
                return false;
            }
            invitation.markUsed(usedBy);
            logger.debug("Marked invitation {} as used by {}", code, usedBy);
            return true;
        }
    }

    @Override
    public int claimInvitationUse(String code, UUID playerId, long now) throws DatabaseException {
        checkConnection();
        Invitation invitation = invitations.get(code);
        if (invitation == null) {
            return 0;
        }
        // Atomic check-and-claim mirroring the SQL guard: the whole predicate
        // (not used, not revoked, usedCount < maxUses) is evaluated under the
        // invitation's monitor, so two concurrent claims cannot both advance
        // usedCount past maxUses. On success we record one use via incrementUse.
        //
        // Note: `now` is honored by the JDBC/Redis providers (bound to the
        // used_at column). The in-memory provider cannot stamp an arbitrary
        // timestamp without a setter on Invitation (intentionally frozen), so
        // it records used_at through incrementUse's own clock — consistent with
        // how markInvitationUsed above uses markUsed. Tests do not assert exact
        // used_at values against MemoryProvider.
        synchronized (invitation) {
            if (invitation.isUsed() || invitation.isRevoked()
                    || invitation.getUsedCount() >= invitation.getMaxUses()) {
                logger.debug("claimInvitationUse({}) rejected — exhausted/revoked/raced", code);
                return 0;
            }
            invitation.incrementUse(playerId);
            logger.debug("Claimed one use of invitation {} for player {}", code, playerId);
            return 1;
        }
    }

    @Override
    public void deleteInvitation(String code) throws DatabaseException {
        checkConnection();
        if (code != null) {
            invitations.remove(code);
            logger.debug("Deleted invitation: {}", code);
        }
    }

    @Override
    public int cleanupExpiredInvitations() throws DatabaseException {
        checkConnection();
        int count = 0;
        long now = System.currentTimeMillis();
        Iterator<Invitation> iterator = invitations.values().iterator();
        while (iterator.hasNext()) {
            Invitation invitation = iterator.next();
            if (now > invitation.getExpireTime()) {
                iterator.remove();
                count++;
            }
        }
        if (count > 0) {
            logger.debug("Cleaned up {} expired invitations", count);
        }
        return count;
    }

    // ==================== Message History Operations (schema v5) ====================

    @Override
    public void saveMessage(ChatMessageRecord message) throws DatabaseException {
        checkConnection();
        if (message == null) {
            throw new DatabaseException("Message cannot be null");
        }
        synchronized (messages) {
            message.setId(++messageIdSeq);
            messages.addLast(message);
            while (messages.size() > MAX_MESSAGES) {
                messages.removeFirst();
            }
        }
        logger.debug("Saved message {} in channel {}", message.getId(), message.getChannelId());
    }

    @Override
    public List<ChatMessageRecord> searchMessages(MessageFilter filter, int offset, int limit) throws DatabaseException {
        checkConnection();
        List<ChatMessageRecord> matched = filterMessagesNewestFirst(filter);
        int start = Math.max(0, offset);
        if (start >= matched.size() || limit <= 0) {
            return new ArrayList<>();
        }
        int end = Math.min(matched.size(), start + limit);
        return new ArrayList<>(matched.subList(start, end));
    }

    @Override
    public int countMessages(MessageFilter filter) throws DatabaseException {
        checkConnection();
        return filterMessagesNewestFirst(filter).size();
    }

    @Override
    public int cleanupMessagesBefore(long cutoffTimestamp) throws DatabaseException {
        checkConnection();
        int count = 0;
        synchronized (messages) {
            Iterator<ChatMessageRecord> iterator = messages.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getTimestamp() < cutoffTimestamp) {
                    iterator.remove();
                    count++;
                }
            }
        }
        if (count > 0) {
            logger.debug("Cleaned up {} expired messages", count);
        }
        return count;
    }

    private List<ChatMessageRecord> filterMessagesNewestFirst(MessageFilter filter) {
        List<ChatMessageRecord> matched = new ArrayList<>();
        synchronized (messages) {
            // Insertion order is oldest-first; iterate in reverse for newest-first.
            Iterator<ChatMessageRecord> it = messages.descendingIterator();
            while (it.hasNext()) {
                ChatMessageRecord record = it.next();
                if (filter.matches(record)) {
                    matched.add(record);
                }
            }
        }
        return matched;
    }

    // ==================== Announcement Operations (schema v5) ====================

    @Override
    public void saveAnnouncement(com.nova.link.announcement.Announcement announcement) throws DatabaseException {
        checkConnection();
        if (announcement == null || announcement.getId() == null) {
            throw new DatabaseException("Announcement and ID cannot be null");
        }
        announcements.put(announcement.getId(), announcement);
        logger.debug("Saved announcement: {}", announcement.getId());
    }

    @Override
    public void deleteAnnouncement(String announcementId) throws DatabaseException {
        checkConnection();
        if (announcementId != null) {
            announcements.remove(announcementId);
            logger.debug("Deleted announcement: {}", announcementId);
        }
    }

    @Override
    public List<com.nova.link.announcement.Announcement> getAllPersistedAnnouncements() throws DatabaseException {
        checkConnection();
        List<com.nova.link.announcement.Announcement> result = new ArrayList<>(announcements.values());
        result.sort(Comparator.comparingLong(com.nova.link.announcement.Announcement::getCreatedAt));
        return result;
    }

    // ==================== Webhook Operations (schema v5) ====================

    @Override
    public void saveWebhook(com.nova.link.api.Webhook webhook) throws DatabaseException {
        checkConnection();
        if (webhook == null || webhook.getId() == null) {
            throw new DatabaseException("Webhook and ID cannot be null");
        }
        persistedWebhooks.put(webhook.getId(), webhook);
        logger.debug("Saved webhook: {}", webhook.getId());
    }

    @Override
    public void deleteWebhook(String webhookId) throws DatabaseException {
        checkConnection();
        if (webhookId != null) {
            persistedWebhooks.remove(webhookId);
            logger.debug("Deleted webhook: {}", webhookId);
        }
    }

    @Override
    public List<com.nova.link.api.Webhook> getAllPersistedWebhooks() throws DatabaseException {
        checkConnection();
        List<com.nova.link.api.Webhook> result = new ArrayList<>(persistedWebhooks.values());
        result.sort(Comparator.comparingLong(com.nova.link.api.Webhook::getCreatedAt));
        return result;
    }

    @Override
    public String getProviderType() {
        return "Memory";
    }

    private void checkConnection() throws DatabaseException {
        if (!connected) {
            throw new DatabaseException("MemoryProvider is not initialized");
        }
    }

    /**
     * Gets the current count of stored player states (for testing).
     */
    public int getPlayerStateCount() {
        return playerStates.size();
    }

    /**
     * Gets the current count of stored channels (for testing).
     */
    public int getChannelCount() {
        return channels.size();
    }

    /**
     * Gets the current count of stored invitations (for testing).
     */
    public int getInvitationCount() {
        return invitations.size();
    }

    // ==================== Config History (schema v12) ====================
    //
    // §11.6 Project 20 / PANEL proposal 10 — in-memory mirror of the JDBC
    // config_history store. The list is append-only; the active flag is flipped
    // on insert. Id sequence stamps the snapshot via reflection, matching the
    // audit-event / evidence id-stamping pattern.

    private final List<com.nova.link.config.ConfigSnapshot> configSnapshots = Collections.synchronizedList(new ArrayList<>());
    private long configSnapshotIdSeq = 0;

    @Override
    public void saveConfigSnapshot(com.nova.link.config.ConfigSnapshot snapshot) throws DatabaseException {
        checkConnection();
        if (snapshot == null) {
            throw new DatabaseException("Cannot save a null config snapshot");
        }
        synchronized (configSnapshots) {
            long id = ++configSnapshotIdSeq;
            snapshot.setId(id);
            snapshot.setActive(true);
            for (com.nova.link.config.ConfigSnapshot existing : configSnapshots) {
                existing.setActive(false);
            }
            configSnapshots.add(snapshot);
        }
        logger.debug("Saved config snapshot revision={} (active=true)", snapshot.getRevision());
    }

    @Override
    public List<com.nova.link.config.ConfigSnapshot> getConfigHistory(int limit) throws DatabaseException {
        checkConnection();
        int effectiveLimit = Math.max(0, limit);
        List<com.nova.link.config.ConfigSnapshot> sorted = new ArrayList<>();
        synchronized (configSnapshots) {
            for (com.nova.link.config.ConfigSnapshot s : configSnapshots) {
                // Metadata-only copy: snapshot_json deliberately omitted so the
                // history list never leaks the (masked) payload.
                sorted.add(new com.nova.link.config.ConfigSnapshot(
                        s.getId(), s.getRevision(), null,
                        s.getCreatedAt(), s.getCreatedBy(), s.isActive()));
            }
        }
        sorted.sort((a, b) -> {
            int byCreated = Long.compare(b.getCreatedAt(), a.getCreatedAt());
            return byCreated != 0 ? byCreated : Long.compare(b.getId(), a.getId());
        });
        if (sorted.size() > effectiveLimit) {
            sorted = new ArrayList<>(sorted.subList(0, effectiveLimit));
        }
        return sorted;
    }

    @Override
    public Optional<com.nova.link.config.ConfigSnapshot> getConfigSnapshot(long revision) throws DatabaseException {
        checkConnection();
        synchronized (configSnapshots) {
            // If multiple rows share a revision (the rollback path appends a
            // new active row with the freshly-bumped revision, but revisions
            // are monotonic so duplicates are unexpected), prefer the newest.
            com.nova.link.config.ConfigSnapshot match = null;
            for (com.nova.link.config.ConfigSnapshot s : configSnapshots) {
                if (s.getRevision() == revision) {
                    if (match == null || s.getId() > match.getId()) {
                        match = s;
                    }
                }
            }
            if (match == null) {
                return Optional.empty();
            }
            return Optional.of(new com.nova.link.config.ConfigSnapshot(
                    match.getId(), match.getRevision(), match.getSnapshotJson(),
                    match.getCreatedAt(), match.getCreatedBy(), match.isActive()));
        }
    }

    @Override
    public int countConfigSnapshots() throws DatabaseException {
        checkConnection();
        synchronized (configSnapshots) {
            return configSnapshots.size();
        }
    }

    @Override
    public int deactivateOtherSnapshots(long activeRevision) throws DatabaseException {
        checkConnection();
        int deactivated = 0;
        synchronized (configSnapshots) {
            for (com.nova.link.config.ConfigSnapshot s : configSnapshots) {
                if (activeRevision < 0 || s.getRevision() != activeRevision) {
                    if (s.isActive()) {
                        s.setActive(false);
                        deactivated++;
                    }
                }
            }
        }
        return deactivated;
    }

    // ==================== Social Relations (schema v13 / 提案 08) ====================
    //
    // §11.6 item-18 / PANEL proposal 08 — in-memory mirror of the JDBC
    // social_relations + notification_preferences stores. Relations are
    // directional and upserted on the composite key (sourceId, targetId, type)
    // inside a per-source ConcurrentHashMap.compute, mirroring the
    // IgnoreListService thread-safety idiom: the compute lambda removes any
    // prior matching relation then adds the new one, so concurrent saves of
    // the same key are linearized. The per-source set is a synchronized Set so
    // the read paths (isIgnored / getSocialRelations) can iterate safely.
    // Platforms without persistence (RedisProvider) inherit the throwing
    // DatabaseProvider defaults and degrade safely; this MemoryProvider is the
    // session-memory fallback the proposal calls for, and warns (via the
    // initialize() log line) that data does not persist across restarts.

    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, java.util.Set<com.nova.link.social.SocialRelation>> socialRelationsBySource = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, com.nova.link.social.NotificationPreference> notificationPreferences = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public boolean isIgnored(java.util.UUID sourceId, java.util.UUID targetId) {
        // Null-safe: unresolved player ids short-circuit to false instead of
        // throwing — callers can pass freshly-resolved ids without a try/catch.
        if (sourceId == null || targetId == null) {
            return false;
        }
        java.util.Set<com.nova.link.social.SocialRelation> relations = socialRelationsBySource.get(sourceId);
        if (relations == null) {
            return false;
        }
        com.nova.link.social.SocialRelation.RelationType ignore = com.nova.link.social.SocialRelation.RelationType.IGNORE;
        synchronized (relations) {
            for (com.nova.link.social.SocialRelation relation : relations) {
                if (relation.getType() == ignore
                        && java.util.Objects.equals(relation.getTargetId(), targetId)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public java.util.List<com.nova.link.social.SocialRelation> getSocialRelations(
            java.util.UUID sourceId, com.nova.link.social.SocialRelation.RelationType type) {
        java.util.List<com.nova.link.social.SocialRelation> results = new java.util.ArrayList<>();
        if (sourceId == null || type == null) {
            return results;
        }
        java.util.Set<com.nova.link.social.SocialRelation> relations = socialRelationsBySource.get(sourceId);
        if (relations == null) {
            return results;
        }
        synchronized (relations) {
            for (com.nova.link.social.SocialRelation relation : relations) {
                if (relation.getType() == type) {
                    results.add(relation);
                }
            }
        }
        // Newest-first by createdAt, matching the JDBC ORDER BY created_at DESC.
        results.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return results;
    }

    @Override
    public void saveSocialRelation(com.nova.link.social.SocialRelation relation) throws DatabaseException {
        checkConnection();
        if (relation == null) {
            throw new DatabaseException("Cannot save a null social relation");
        }
        if (relation.getSourceId() == null || relation.getTargetId() == null || relation.getType() == null) {
            throw new DatabaseException("Social relation sourceId/targetId/type must not be null");
        }
        // Upsert on the composite key: remove any prior matching relation for
        // the same (sourceId, targetId, type) then add the new one, atomically
        // under the per-source compute. Mirrors IgnoreListService.compute.
        socialRelationsBySource.compute(relation.getSourceId(), (id, existing) -> {
            java.util.Set<com.nova.link.social.SocialRelation> set = existing;
            if (set == null) {
                set = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
            }
            synchronized (set) {
                set.remove(relation); // equals/hashCode are the composite key
                set.add(relation);
            }
            return set;
        });
        logger.debug("Saved social relation source={} target={} type={}",
                relation.getSourceId(), relation.getTargetId(), relation.getType());
    }

    @Override
    public void removeSocialRelation(java.util.UUID sourceId, java.util.UUID targetId,
                                     com.nova.link.social.SocialRelation.RelationType type) {
        if (sourceId == null || targetId == null || type == null) {
            return;
        }
        socialRelationsBySource.computeIfPresent(sourceId, (id, set) -> {
            synchronized (set) {
                set.removeIf(relation ->
                        relation.getType() == type
                                && java.util.Objects.equals(relation.getTargetId(), targetId));
            }
            // Drop the source entry entirely when empty so isIgnored/getSocialRelations
            // see no stale empty sets (and shutdown().clear() stays simple).
            return set.isEmpty() ? null : set;
        });
    }

    @Override
    public com.nova.link.social.NotificationPreference getNotificationPreference(java.util.UUID playerId) {
        if (playerId == null) {
            return com.nova.link.social.NotificationPreference.defaults(null);
        }
        com.nova.link.social.NotificationPreference stored = notificationPreferences.get(playerId);
        return stored != null ? stored : com.nova.link.social.NotificationPreference.defaults(playerId);
    }

    @Override
    public void saveNotificationPreference(com.nova.link.social.NotificationPreference preference) throws DatabaseException {
        checkConnection();
        if (preference == null) {
            throw new DatabaseException("Cannot save a null notification preference");
        }
        if (preference.getPlayerId() == null) {
            throw new DatabaseException("Notification preference playerId must not be null");
        }
        notificationPreferences.put(preference.getPlayerId(), preference);
        logger.debug("Saved notification preference player={} mentionsEnabled={}",
                preference.getPlayerId(), preference.isMentionsEnabled());
    }

    // ==================== Campaign Operations (schema v14 / 提案 06) ====================
    //
    // §11.6 item-19 slice B / PANEL proposal 08 — in-memory mirror of the JDBC
    // campaigns store. A simple ConcurrentHashMap keyed by campaign id provides
    // the same CRUD semantics as the JDBC provider. MemoryProvider is the
    // session-memory fallback; data does not persist across restarts (warned
    // by the initialize() log line).

    @Override
    public void saveCampaign(com.nova.link.announcement.Campaign campaign) throws DatabaseException {
        checkConnection();
        if (campaign == null || campaign.getId() == null) {
            throw new DatabaseException("Campaign and ID cannot be null");
        }
        campaigns.put(campaign.getId(), campaign);
        logger.debug("Saved campaign: {}", campaign.getId());
    }

    @Override
    public java.util.Optional<com.nova.link.announcement.Campaign> getCampaign(String id) {
        if (id == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(campaigns.get(id));
    }

    @Override
    public java.util.List<com.nova.link.announcement.Campaign> getAllPersistedCampaigns() {
        java.util.List<com.nova.link.announcement.Campaign> result = new ArrayList<>(campaigns.values());
        result.sort(Comparator.comparingLong(com.nova.link.announcement.Campaign::getCreatedAt));
        return result;
    }

    @Override
    public void deleteCampaign(String id) {
        if (id != null) {
            campaigns.remove(id);
            logger.debug("Deleted campaign: {}", id);
        }
    }

    @Override
    public void updateCampaignStatus(String id, com.nova.link.announcement.CampaignStatus status,
                                     long revokedAt, java.util.UUID revokedBy) {
        if (id == null || status == null) {
            return;
        }
        com.nova.link.announcement.Campaign campaign = campaigns.get(id);
        if (campaign == null) {
            return;
        }
        // Campaign mutations are package-private; MemoryProvider lives in a
        // different package than Campaign (com.nova.link.database vs
        // com.nova.link.announcement), so reflection is used to apply the
        // state transition. State-machine validity is CampaignManager's
        // responsibility — this is a blind persistence mirror, matching the
        // JDBC provider's behaviour (which also does not validate transitions).
        if (status == com.nova.link.announcement.CampaignStatus.REVOKED) {
            // markRevoked stamps revokedAt/revokedBy AND forces status=REVOKED.
            try {
                java.lang.reflect.Method m = com.nova.link.announcement.Campaign.class
                        .getDeclaredMethod("markRevoked", long.class, java.util.UUID.class);
                m.setAccessible(true);
                m.invoke(campaign, revokedAt, revokedBy);
            } catch (ReflectiveOperationException e) {
                logger.warn("Could not invoke Campaign.markRevoked for {}: {}", id, e.getMessage());
            }
        } else {
            try {
                java.lang.reflect.Method m = com.nova.link.announcement.Campaign.class
                        .getDeclaredMethod("setStatus", com.nova.link.announcement.CampaignStatus.class);
                m.setAccessible(true);
                m.invoke(campaign, status);
            } catch (ReflectiveOperationException e) {
                logger.warn("Could not invoke Campaign.setStatus for {}: {}", id, e.getMessage());
            }
        }
        logger.debug("Updated campaign status: {} -> {}", id, status);
    }

    // ==================== Config Drafts (schema v15 / proposal 10) ====================
    //
    // §11.6 item-20 / PANEL proposal 10 — in-memory mirror of the JDBC
    // config_drafts store. A synchronized list keyed by id provides the same
    // CRUD semantics as the JDBC provider; the id sequence stamps the draft
    // via reflection, matching the config_snapshot id-stamping pattern.

    private final List<com.nova.link.api.ConfigDraft> configDrafts = Collections.synchronizedList(new ArrayList<>());
    private long configDraftIdSeq = 0;

    @Override
    public void saveConfigDraft(com.nova.link.api.ConfigDraft draft) throws DatabaseException {
        checkConnection();
        if (draft == null) {
            throw new DatabaseException("Cannot save a null config draft");
        }
        synchronized (configDrafts) {
            long id = ++configDraftIdSeq;
            draft.setId(id);
            configDrafts.add(draft);
        }
        logger.debug("Saved config draft id={} status={}", draft.getId(), draft.getStatus());
    }

    @Override
    public java.util.Optional<com.nova.link.api.ConfigDraft> getConfigDraft(long id) throws DatabaseException {
        checkConnection();
        synchronized (configDrafts) {
            for (com.nova.link.api.ConfigDraft d : configDrafts) {
                if (d.getId() == id) {
                    return java.util.Optional.of(new com.nova.link.api.ConfigDraft(
                            d.getId(), d.getDraftJson(), d.getCreatedBy(), d.getStatus(),
                            d.getApprovedBy(), d.getCreatedAt(), d.getApprovedAt(), d.getPublishedAt()));
                }
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public List<com.nova.link.api.ConfigDraft> listConfigDrafts(int limit) throws DatabaseException {
        checkConnection();
        int effectiveLimit = Math.max(0, limit);
        List<com.nova.link.api.ConfigDraft> sorted = new ArrayList<>();
        synchronized (configDrafts) {
            for (com.nova.link.api.ConfigDraft d : configDrafts) {
                // Metadata-only copy: draft_json deliberately omitted so the
                // history list never leaks the (masked) payload.
                sorted.add(new com.nova.link.api.ConfigDraft(
                        d.getId(), null, d.getCreatedBy(), d.getStatus(),
                        d.getApprovedBy(), d.getCreatedAt(), d.getApprovedAt(), d.getPublishedAt()));
            }
        }
        sorted.sort((a, b) -> {
            int byCreated = Long.compare(b.getCreatedAt(), a.getCreatedAt());
            return byCreated != 0 ? byCreated : Long.compare(b.getId(), a.getId());
        });
        if (sorted.size() > effectiveLimit) {
            sorted = new ArrayList<>(sorted.subList(0, effectiveLimit));
        }
        return sorted;
    }

    @Override
    public void updateConfigDraftStatus(long id, com.nova.link.api.ConfigDraft.Status status,
                                         String approvedBy, long approvedAt, long publishedAt)
            throws DatabaseException {
        checkConnection();
        if (status == null) {
            return;
        }
        synchronized (configDrafts) {
            for (com.nova.link.api.ConfigDraft d : configDrafts) {
                if (d.getId() == id) {
                    // ConfigDraft state-transition methods are package-private;
                    // MemoryProvider lives in a different package than
                    // ConfigDraft (com.nova.link.database vs com.nova.link.api),
                    // so reflection is used to apply the state transition. This
                    // is a blind persistence mirror; state-machine validity is
                    // ConfigPublishService's responsibility.
                    try {
                        if (status == com.nova.link.api.ConfigDraft.Status.APPROVED) {
                            java.lang.reflect.Method m = com.nova.link.api.ConfigDraft.class
                                    .getDeclaredMethod("markApproved", String.class, long.class);
                            m.setAccessible(true);
                            m.invoke(d, approvedBy, approvedAt);
                        } else if (status == com.nova.link.api.ConfigDraft.Status.PUBLISHED) {
                            java.lang.reflect.Method m = com.nova.link.api.ConfigDraft.class
                                    .getDeclaredMethod("markPublished", long.class);
                            m.setAccessible(true);
                            m.invoke(d, publishedAt);
                        } else {
                            // DRAFT transition (not used by the service) —
                            // fall back to a raw reflection set on the status
                            // field so the mirror stays consistent.
                            java.lang.reflect.Field f = com.nova.link.api.ConfigDraft.class
                                    .getDeclaredField("status");
                            f.setAccessible(true);
                            f.set(d, status);
                        }
                    } catch (ReflectiveOperationException e) {
                        logger.warn("Could not apply draft state transition id={} status={}: {}",
                                id, status, e.getMessage());
                    }
                    break;
                }
            }
        }
        logger.debug("Updated config draft status: {} -> {}", id, status);
    }

    @Override
    public void deleteConfigDraft(long id) throws DatabaseException {
        checkConnection();
        synchronized (configDrafts) {
            configDrafts.removeIf(d -> d.getId() == id);
        }
        logger.debug("Deleted config draft id={}", id);
    }

    // ==================== Config Backups (schema v15 / proposal 10) ====================
    //
    // §11.6 item-20 / PANEL proposal 10 — in-memory mirror of the JDBC
    // config_backups store. A synchronized list keyed by id provides the same
    // CRUD semantics as the JDBC provider; the id sequence stamps the backup
    // via reflection, matching the config_snapshot id-stamping pattern.

    private final List<com.nova.link.api.ConfigBackup> configBackups = Collections.synchronizedList(new ArrayList<>());
    private long configBackupIdSeq = 0;

    @Override
    public void saveConfigBackup(com.nova.link.api.ConfigBackup backup) throws DatabaseException {
        checkConnection();
        if (backup == null) {
            throw new DatabaseException("Cannot save a null config backup");
        }
        synchronized (configBackups) {
            long id = ++configBackupIdSeq;
            backup.setId(id);
            configBackups.add(backup);
        }
        logger.debug("Saved config backup id={} label={}", backup.getId(), backup.getLabel());
    }

    @Override
    public java.util.Optional<com.nova.link.api.ConfigBackup> getConfigBackup(long id) throws DatabaseException {
        checkConnection();
        synchronized (configBackups) {
            for (com.nova.link.api.ConfigBackup b : configBackups) {
                if (b.getId() == id) {
                    return java.util.Optional.of(new com.nova.link.api.ConfigBackup(
                            b.getId(), b.getLabel(), b.getBackupJson(),
                            b.getSettingsRevision(), b.getCreatedBy(), b.getCreatedAt()));
                }
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public List<com.nova.link.api.ConfigBackup> listConfigBackups(int limit) throws DatabaseException {
        checkConnection();
        int effectiveLimit = Math.max(0, limit);
        List<com.nova.link.api.ConfigBackup> sorted = new ArrayList<>();
        synchronized (configBackups) {
            for (com.nova.link.api.ConfigBackup b : configBackups) {
                // Metadata-only copy: backup_json deliberately omitted so the
                // history list never leaks the (masked) payload.
                sorted.add(new com.nova.link.api.ConfigBackup(
                        b.getId(), b.getLabel(), null,
                        b.getSettingsRevision(), b.getCreatedBy(), b.getCreatedAt()));
            }
        }
        sorted.sort((a, b) -> {
            int byCreated = Long.compare(b.getCreatedAt(), a.getCreatedAt());
            return byCreated != 0 ? byCreated : Long.compare(b.getId(), a.getId());
        });
        if (sorted.size() > effectiveLimit) {
            sorted = new ArrayList<>(sorted.subList(0, effectiveLimit));
        }
        return sorted;
    }
}
