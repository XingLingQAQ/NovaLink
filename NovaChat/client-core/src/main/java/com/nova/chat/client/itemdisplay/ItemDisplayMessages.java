package com.nova.chat.client.itemdisplay;

import com.nova.chat.client.i18n.I18n;

import java.util.UUID;

/**
 * Shared, platform-agnostic chat-line rendering for inbound
 * {@code ItemDisplayPacket}s (0x10) — the {@code [item]}/{@code [i]} display
 * play (UX spec §4 display family, Requirements 12.x).
 *
 * <p>Alignment with the Bedrock reference clients (pmmp
 * {@code ChatHandler::handleItemDisplay}, endstone
 * {@code ChatHandler._handle_item_display}, levilamina
 * {@code ChatInterceptor} ITEM_DISPLAY handler): the packet renders as a single
 * chat line combining the sender name and the item payload, e.g. endstone's
 * {@code §7{sender} §f[§bitem§f]§7: {json}}. This class renders the same shape
 * but resolves the payload into a readable item name + count via
 * {@link ItemDisplayInfo} and localizes the copy through {@link I18n}
 * (zh_CN / en_US bundles in {@code lang/}).
 *
 * <p>All returned strings use the shared {@code &} color form; platforms run
 * their own color translation (exactly as their Title handlers do) before
 * sending.
 *
 * <p>Architecture B: plugin-only. Never imported by {@code novalink-core}.
 */
public final class ItemDisplayMessages {

    /** i18n key: single-item line — {0}=sender, {1}=item name. */
    public static final String KEY_LINE = "chat.item.display_line";

    /** i18n key: multi-item line — {0}=sender, {1}=item name, {2}=count. */
    public static final String KEY_LINE_COUNT = "chat.item.display_line_count";

    /** i18n key: empty-hand placeholder name (Bedrock renders {@code §7§oEmpty}). */
    public static final String KEY_EMPTY = "chat.item.empty";

    /** i18n key: hover detail id row — {0}=item id. */
    public static final String KEY_HOVER_ID = "chat.item.hover.id";

    /** i18n key: hover detail count row — {0}=count. */
    public static final String KEY_HOVER_COUNT = "chat.item.hover.count";

    private ItemDisplayMessages() {
        // Utility class — no instances.
    }

    /**
     * Formats an inbound item display as one localized, {@code &}-colored chat
     * line: sender + item name (+ count when stacked).
     *
     * @param viewerId   the recipient (locale lookup); may be null → default locale
     * @param senderName the sender's display name; null renders as empty
     * @param itemJson   the packet's item payload; parsed leniently
     * @return the formatted line, still in {@code &} color form
     */
    public static String formatLine(UUID viewerId, String senderName, String itemJson) {
        ItemDisplayInfo info = ItemDisplayInfo.fromJson(itemJson);
        String sender = senderName != null ? senderName : "";
        String itemName = info.isEmpty()
                ? I18n.tr(viewerId, KEY_EMPTY)
                : info.resolveDisplayName();
        if (!info.isEmpty() && info.getCount() > 1) {
            // Count passed as a String so MessageFormat does not apply locale
            // digit grouping (1,234) to stack sizes.
            return I18n.tr(viewerId, KEY_LINE_COUNT, sender, itemName, String.valueOf(info.getCount()));
        }
        return I18n.tr(viewerId, KEY_LINE, sender, itemName);
    }

    /**
     * Formats the multi-line hover detail (name / id / count) for platforms
     * whose chat API supports hover components (e.g. Bukkit). Lines are
     * separated by {@code \n} and use the shared {@code &} color form.
     *
     * @param viewerId the recipient (locale lookup); may be null → default locale
     * @param itemJson the packet's item payload; parsed leniently
     * @return the hover text, still in {@code &} color form
     */
    public static String formatHoverDetail(UUID viewerId, String itemJson) {
        ItemDisplayInfo info = ItemDisplayInfo.fromJson(itemJson);
        if (info.isEmpty()) {
            return I18n.tr(viewerId, KEY_EMPTY);
        }
        StringBuilder sb = new StringBuilder("&f").append(info.resolveDisplayName());
        if (!info.getId().isBlank()) {
            sb.append('\n').append(I18n.tr(viewerId, KEY_HOVER_ID, info.getId()));
        }
        sb.append('\n').append(I18n.tr(viewerId, KEY_HOVER_COUNT, String.valueOf(info.getCount())));
        return sb.toString();
    }
}
