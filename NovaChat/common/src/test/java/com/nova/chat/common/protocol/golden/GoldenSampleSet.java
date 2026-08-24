package com.nova.chat.common.protocol.golden;

import com.google.gson.JsonObject;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.VarInt;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Deterministic golden sample definitions for all 13 packet types registered
 * by {@code NovaProtocol.createRegistry()}.
 *
 * <p>Each sample is a complete NovaProtocol v2 frame
 * {@code Length(VarInt) | PacketID(1B) | RequestID(UUID 16B) | Payload} plus a
 * JSON descriptor with the expected field values. The Java implementation is
 * the protocol authority: these bytes are produced by the Java encoders.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Request IDs are fixed and incrementing:
 *       {@code 00000000-0000-0000-0000-00000000002a} onwards.</li>
 *   <li>Maps carry at most one entry so that re-encode byte equality does not
 *       depend on map iteration order in any language.</li>
 *   <li>{@code legacyWire} samples omit optional trailing fields on the wire;
 *       decoding them must succeed, but re-encoding appends the canonical
 *       trailing fields, so byte-exact comparison is skipped
 *       ({@code reencodeExact=false}).</li>
 *   <li>{@code knownDrift} records, per language, documented behavioural
 *       differences. Language test suites skip the byte-exact re-encode
 *       comparison for their own key and report it as a known drift.
 *       (Currently no sample carries a drift: the historical PHP/Python/C++
 *       ChatMessage-placeholders and ConfigSync-empty-string drifts were
 *       fixed.)</li>
 * </ul>
 */
public final class GoldenSampleSet {

    /** One golden sample: descriptor JSON + full frame bytes. */
    public static final class Sample {
        public final String name;
        public final JsonObject json;
        public final byte[] frame;

        Sample(String name, JsonObject json, byte[] frame) {
            this.name = name;
            this.json = json;
            this.frame = frame;
        }
    }

    private static final UUID UUID_A = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00");
    private static final UUID UUID_B = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
    private static final UUID UUID_NIL = new UUID(0L, 0L);

    private static final long TS_TYPICAL = 1755057600000L;

    private GoldenSampleSet() {
    }

    private static UUID rid(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }

    public static List<Sample> samples() {
        List<Sample> out = new ArrayList<>();
        int seq = 0x2a;

        // ==================== 0x01 HANDSHAKE ====================
        {
            HandshakePacket p = new HandshakePacket(
                    2, "bedrock-lobby-01",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    PlatformType.POCKETMINE, "1.21.44");
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("protocolVersion", 2);
            f.addProperty("clientId", "bedrock-lobby-01");
            f.addProperty("passwordHash",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            f.addProperty("platform", PlatformType.POCKETMINE.getId());
            f.addProperty("serverVersion", "1.21.44");
            out.add(make("handshake_v2_full", "HandshakePacket", PacketIds.HANDSHAKE, reqId,
                    false, true, null,
                    "协议 v2 完整握手：含尾部 serverVersion 字段", f, frameOf(p)));
        }
        {
            // Legacy v1 wire: trailing serverVersion absent. Hand-crafted payload.
            UUID reqId = rid(seq++);
            byte[] frame = legacyFrame(PacketIds.HANDSHAKE, reqId, buf -> {
                PacketBuffer.writeVarInt(buf, 1);
                PacketBuffer.writeString(buf, "老服-①区");
                PacketBuffer.writeString(buf, "a1b2c3");
                buf.writeByte(PlatformType.LEVILAMINA.getId());
            });
            JsonObject f = new JsonObject();
            f.addProperty("protocolVersion", 1);
            f.addProperty("clientId", "老服-①区");
            f.addProperty("passwordHash", "a1b2c3");
            f.addProperty("platform", PlatformType.LEVILAMINA.getId());
            f.addProperty("serverVersion", "");
            out.add(make("handshake_v1_no_serverversion", "HandshakePacket", PacketIds.HANDSHAKE,
                    reqId, true, false, null,
                    "遗留 v1 握手：wire 上缺省尾部 serverVersion（中文 clientId），解码应得空串；"
                            + "re-encode 会补写空 serverVersion，故不做字节比对", f, frame));
        }

        // ==================== 0x02 HANDSHAKE_RESPONSE ====================
        {
            HandshakeResponsePacket p = new HandshakeResponsePacket(true, "", "欢迎 Welcome ✨");
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("success", true);
            f.addProperty("errorCode", "");
            f.addProperty("message", "欢迎 Welcome ✨");
            out.add(make("handshake_response_success", "HandshakeResponsePacket",
                    PacketIds.HANDSHAKE_RESPONSE, reqId, false, true, null,
                    "握手成功响应：空 errorCode + 中文/emoji message", f, frameOf(p)));
        }
        {
            HandshakeResponsePacket p = new HandshakeResponsePacket(false, "NC-420",
                    "Protocol version mismatch: v1 rejected");
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("success", false);
            f.addProperty("errorCode", "NC-420");
            f.addProperty("message", "Protocol version mismatch: v1 rejected");
            out.add(make("handshake_response_failure", "HandshakeResponsePacket",
                    PacketIds.HANDSHAKE_RESPONSE, reqId, false, true, null,
                    "握手失败响应：NC-420（字段顺序 success|errorCode|message）", f, frameOf(p)));
        }

        // ==================== 0x03 CHAT_MESSAGE ====================
        {
            ChatMessagePacket p = new ChatMessagePacket(UUID_A, "Steve", "survival-01",
                    "global", "Hello, 世界! 🎉");
            Map<String, String> placeholders = new LinkedHashMap<>();
            placeholders.put("player_level", "42");
            p.setPlaceholders(placeholders);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject ph = new JsonObject();
            ph.addProperty("player_level", "42");
            JsonObject f = new JsonObject();
            f.addProperty("senderId", UUID_A.toString());
            f.addProperty("senderName", "Steve");
            f.addProperty("clientId", "survival-01");
            f.addProperty("channelId", "global");
            f.addProperty("content", "Hello, 世界! 🎉");
            f.add("placeholders", ph);
            out.add(make("chat_message_full", "ChatMessagePacket", PacketIds.CHAT_MESSAGE, reqId,
                    false, true, null,
                    "聊天消息：中文+emoji 内容，placeholders 非空（1 项，保证跨语言 map 序确定）", f, frameOf(p)));
        }
        {
            // Legacy wire: frame ends right after content (no placeholders varint).
            UUID reqId = rid(seq++);
            byte[] frame = legacyFrame(PacketIds.CHAT_MESSAGE, reqId, buf -> {
                PacketBuffer.writeUUID(buf, UUID_B);
                PacketBuffer.writeString(buf, "");
                PacketBuffer.writeString(buf, "");
                PacketBuffer.writeString(buf, "lobby");
                PacketBuffer.writeString(buf, "");
            });
            JsonObject f = new JsonObject();
            f.addProperty("senderId", UUID_B.toString());
            f.addProperty("senderName", "");
            f.addProperty("clientId", "");
            f.addProperty("channelId", "lobby");
            f.addProperty("content", "");
            f.add("placeholders", new JsonObject());
            out.add(make("chat_message_legacy_no_placeholders", "ChatMessagePacket",
                    PacketIds.CHAT_MESSAGE, reqId, true, false, null,
                    "遗留聊天消息：payload 在 content 后结束（无 placeholders 计数），"
                            + "空字符串边界；re-encode 会补写 varint 0，不做字节比对", f, frame));
        }

        // ==================== 0x04 CHANNEL_ACTION ====================
        {
            ChannelActionPacket p = new ChannelActionPacket(ChannelAction.CREATE, "vip-频道", "s3cret!");
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("displayName", "VIP 频道 ⭐");
            p.setExtra(extra);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject ex = new JsonObject();
            ex.addProperty("displayName", "VIP 频道 ⭐");
            JsonObject f = new JsonObject();
            f.addProperty("action", ChannelAction.CREATE.getId());
            f.addProperty("channelId", "vip-频道");
            f.addProperty("password", "s3cret!");
            f.add("extra", ex);
            out.add(make("channel_action_create_extra", "ChannelActionPacket",
                    PacketIds.CHANNEL_ACTION, reqId, false, true, null,
                    "创建频道：中文 channelId + 密码 + 非空 extra（1 项）", f, frameOf(p)));
        }
        {
            ChannelActionPacket p = new ChannelActionPacket(ChannelAction.JOIN, "global", "");
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("action", ChannelAction.JOIN.getId());
            f.addProperty("channelId", "global");
            f.addProperty("password", "");
            f.add("extra", new JsonObject());
            out.add(make("channel_action_join_empty_extra", "ChannelActionPacket",
                    PacketIds.CHANNEL_ACTION, reqId, false, true, null,
                    "加入频道：空密码 + 显式写出的空 extra map（varint 0）", f, frameOf(p)));
        }

        // ==================== 0x05 CHANNEL_ACTION_RESPONSE ====================
        {
            ChannelActionResponsePacket p = new ChannelActionResponsePacket(true,
                    ChannelAction.JOIN, "global", "", "已加入频道");
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("memberCount", "17");
            p.setExtra(extra);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject ex = new JsonObject();
            ex.addProperty("memberCount", "17");
            JsonObject f = new JsonObject();
            f.addProperty("success", true);
            f.addProperty("action", ChannelAction.JOIN.getId());
            f.addProperty("channelId", "global");
            f.addProperty("errorCode", "");
            f.addProperty("message", "已加入频道");
            f.add("extra", ex);
            out.add(make("channel_action_response_ok_extra", "ChannelActionResponsePacket",
                    PacketIds.CHANNEL_ACTION_RESPONSE, reqId, false, true, null,
                    "频道操作成功响应：中文 message + 非空 extra（1 项）", f, frameOf(p)));
        }
        {
            ChannelActionResponsePacket p = new ChannelActionResponsePacket(false,
                    ChannelAction.KICK, "vip-频道", "NC-403", "No permission 🚫");
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("success", false);
            f.addProperty("action", ChannelAction.KICK.getId());
            f.addProperty("channelId", "vip-频道");
            f.addProperty("errorCode", "NC-403");
            f.addProperty("message", "No permission 🚫");
            f.add("extra", new JsonObject());
            out.add(make("channel_action_response_error", "ChannelActionResponsePacket",
                    PacketIds.CHANNEL_ACTION_RESPONSE, reqId, false, true, null,
                    "频道操作失败响应：NC-403 + emoji message + 空 extra", f, frameOf(p)));
        }

        // ==================== 0x06 CONFIG_SYNC ====================
        {
            String cfg = "{\"channels\":[\"global\",\"trade\"],\"motd\":\"今日维护 🛠️\"}";
            ConfigSyncPacket p = new ConfigSyncPacket(cfg, TS_TYPICAL);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("configJson", cfg);
            f.addProperty("timestamp", TS_TYPICAL);
            out.add(make("config_sync_typical", "ConfigSyncPacket", PacketIds.CONFIG_SYNC, reqId,
                    false, true, null,
                    "配置同步：嵌套 JSON 字符串（含引号转义与中文/emoji）+ 毫秒时间戳", f, frameOf(p)));
        }
        {
            ConfigSyncPacket p = new ConfigSyncPacket("", 0L);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("configJson", "");
            f.addProperty("timestamp", 0L);
            out.add(make("config_sync_empty_string", "ConfigSyncPacket", PacketIds.CONFIG_SYNC,
                    reqId, false, true, null,
                    "配置同步边界：configJson 为空字符串、timestamp=0——校验各语言不得把空串规范化为 {}", f, frameOf(p)));
        }

        // ==================== 0x07 KEEP_ALIVE ====================
        {
            KeepAlivePacket p = new KeepAlivePacket(TS_TYPICAL);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("timestamp", TS_TYPICAL);
            out.add(make("keep_alive_typical", "KeepAlivePacket", PacketIds.KEEP_ALIVE, reqId,
                    false, true, null,
                    "心跳：典型毫秒时间戳", f, frameOf(p)));
        }
        {
            KeepAlivePacket p = new KeepAlivePacket(Long.MAX_VALUE);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("timestamp", Long.MAX_VALUE);
            out.add(make("keep_alive_max_long", "KeepAlivePacket", PacketIds.KEEP_ALIVE, reqId,
                    false, true, null,
                    "心跳边界：timestamp = Long.MAX_VALUE（0x7FFFFFFFFFFFFFFF）", f, frameOf(p)));
        }

        // ==================== 0x09 TITLE ====================
        {
            TitlePacket p = new TitlePacket("global", "§6服务器公告",
                    "Server restart in 5 min ⏰", UUID_A, 10, 70, 20);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("channelId", "global");
            f.addProperty("title", "§6服务器公告");
            f.addProperty("subtitle", "Server restart in 5 min ⏰");
            f.addProperty("fadeIn", 10);
            f.addProperty("stay", 70);
            f.addProperty("fadeOut", 20);
            f.addProperty("senderId", UUID_A.toString());
            out.add(make("title_typical", "TitlePacket", PacketIds.TITLE, reqId,
                    false, true, null,
                    "Title：颜色符号 § + 中文标题 + emoji 副标题 + 默认时序", f, frameOf(p)));
        }
        {
            TitlePacket p = new TitlePacket("", "紧急 🔥", "", UUID_NIL, 0, 1, 0);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("channelId", "");
            f.addProperty("title", "紧急 🔥");
            f.addProperty("subtitle", "");
            f.addProperty("fadeIn", 0);
            f.addProperty("stay", 1);
            f.addProperty("fadeOut", 0);
            f.addProperty("senderId", UUID_NIL.toString());
            out.add(make("title_boundary", "TitlePacket", PacketIds.TITLE, reqId,
                    false, true, null,
                    "Title 边界：空 channelId/subtitle、零时序、全零 senderId UUID", f, frameOf(p)));
        }

        // ==================== 0x0B ADMIN_ACTION ====================
        {
            AdminActionPacket p = AdminActionPacket.createAuthPacket(UUID_A,
                    "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8");
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("action", AdminAction.AUTH.getId());
            f.addProperty("playerId", UUID_A.toString());
            f.addProperty("passwordHash",
                    "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8");
            f.addProperty("target", "");
            f.add("extra", new JsonObject());
            out.add(make("admin_action_auth", "AdminActionPacket", PacketIds.ADMIN_ACTION, reqId,
                    false, true, null,
                    "管理员认证：AUTH + SHA-256 哈希 + 空 target + 空 extra", f, frameOf(p)));
        }
        {
            AdminActionPacket p = AdminActionPacket.createSpyStartPacket(UUID_B, "vip-频道");
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("reason", "举报核查 🔍");
            p.setExtra(extra);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject ex = new JsonObject();
            ex.addProperty("reason", "举报核查 🔍");
            JsonObject f = new JsonObject();
            f.addProperty("action", AdminAction.SPY_START.getId());
            f.addProperty("playerId", UUID_B.toString());
            f.addProperty("passwordHash", "");
            f.addProperty("target", "vip-频道");
            f.add("extra", ex);
            out.add(make("admin_action_spy_extra", "AdminActionPacket", PacketIds.ADMIN_ACTION,
                    reqId, false, true, null,
                    "管理员监听：SPY_START + 中文 target + 非空 extra（1 项）", f, frameOf(p)));
        }
        {
            // FEATURE-002: STATUS + type=ANNOUNCE — the unified broadcast path.
            // Mirrors bukkit AnnounceCommand: action=STATUS, target=channelId,
            // extra={type:ANNOUNCE, operatorName, content}.
            AdminActionPacket p = new AdminActionPacket();
            p.setAction(AdminAction.STATUS);
            p.setPlayerId(UUID_A);
            p.setPasswordHash("");
            p.setTarget("global");
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("type", "ANNOUNCE");
            extra.put("operatorName", "Alex");
            extra.put("content", "服务器将在 5 分钟后维护 🔧");
            p.setExtra(extra);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject ex = new JsonObject();
            ex.addProperty("type", "ANNOUNCE");
            ex.addProperty("operatorName", "Alex");
            ex.addProperty("content", "服务器将在 5 分钟后维护 🔧");
            JsonObject f = new JsonObject();
            f.addProperty("action", AdminAction.STATUS.getId());
            f.addProperty("playerId", UUID_A.toString());
            f.addProperty("passwordHash", "");
            f.addProperty("target", "global");
            f.add("extra", ex);
            out.add(make("admin_action_status_announce", "AdminActionPacket", PacketIds.ADMIN_ACTION,
                    reqId, false, true, null,
                    "管理公告：STATUS + type=ANNOUNCE + 中文 content + 3 项 extra（统一广播路径）", f, frameOf(p)));
        }

        // ==================== 0x0C ADMIN_ACTION_RESPONSE ====================
        {
            AdminActionResponsePacket p = AdminActionResponsePacket.success(AdminAction.AUTH,
                    "Authenticated ✔");
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("action", AdminAction.AUTH.getId());
            f.addProperty("success", true);
            f.addProperty("errorCode", "");
            f.addProperty("message", "Authenticated ✔");
            out.add(make("admin_action_response_ok", "AdminActionResponsePacket",
                    PacketIds.ADMIN_ACTION_RESPONSE, reqId, false, true, null,
                    "管理操作成功响应（字段顺序 action|success|errorCode|message）", f, frameOf(p)));
        }
        {
            AdminActionResponsePacket p = AdminActionResponsePacket.failure(AdminAction.RELOAD,
                    "NC-500", "配置重载失败");
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("action", AdminAction.RELOAD.getId());
            f.addProperty("success", false);
            f.addProperty("errorCode", "NC-500");
            f.addProperty("message", "配置重载失败");
            out.add(make("admin_action_response_fail", "AdminActionResponsePacket",
                    PacketIds.ADMIN_ACTION_RESPONSE, reqId, false, true, null,
                    "管理操作失败响应：NC-500 + 中文 message", f, frameOf(p)));
        }

        // ==================== 0x10 ITEM_DISPLAY ====================
        {
            String itemJson = "{\"id\":\"minecraft:netherite_sword\",\"count\":1}";
            ItemDisplayPacket p = new ItemDisplayPacket(UUID_A, "Alex", "global", itemJson,
                    1755057612345L);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("senderId", UUID_A.toString());
            f.addProperty("senderName", "Alex");
            f.addProperty("channelId", "global");
            f.addProperty("itemJson", itemJson);
            f.addProperty("timestamp", 1755057612345L);
            out.add(make("item_display_typical", "ItemDisplayPacket", PacketIds.ITEM_DISPLAY,
                    reqId, false, true, null,
                    "物品展示：嵌套 JSON 字符串 + 毫秒时间戳", f, frameOf(p)));
        }
        {
            ItemDisplayPacket p = new ItemDisplayPacket(UUID_NIL, "", "", "", -1L);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("senderId", UUID_NIL.toString());
            f.addProperty("senderName", "");
            f.addProperty("channelId", "");
            f.addProperty("itemJson", "");
            f.addProperty("timestamp", -1L);
            out.add(make("item_display_boundary", "ItemDisplayPacket", PacketIds.ITEM_DISPLAY,
                    reqId, false, true, null,
                    "物品展示边界：全空字符串、全零 UUID、负 long（-1 → 8 个 0xFF 字节）", f, frameOf(p)));
        }

        // ==================== 0x12 MENTION ====================
        {
            MentionPacket p = new MentionPacket(UUID_A, "Steve", UUID_B, "global",
                    "@Alex 快来看 👀", 1755057699999L);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("mentionerId", UUID_A.toString());
            f.addProperty("mentionerName", "Steve");
            f.addProperty("mentionedId", UUID_B.toString());
            f.addProperty("channelId", "global");
            f.addProperty("messagePreview", "@Alex 快来看 👀");
            f.addProperty("timestamp", 1755057699999L);
            out.add(make("mention_typical", "MentionPacket", PacketIds.MENTION, reqId,
                    false, true, null,
                    "@提及通知：双 UUID + 中文/emoji 预览 + 毫秒时间戳", f, frameOf(p)));
        }

        // ==================== 0x14 PRIVATE_MESSAGE ====================
        {
            // S->C form: backend has resolved targetId and stamped the time.
            PrivateMessagePacket p = new PrivateMessagePacket(UUID_A, "Steve", "survival-01",
                    "Alex", UUID_B, "Meet me at spawn", 1755057712345L);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("senderId", UUID_A.toString());
            f.addProperty("senderName", "Steve");
            f.addProperty("senderClientId", "survival-01");
            f.addProperty("targetName", "Alex");
            f.addProperty("targetId", UUID_B.toString());
            f.addProperty("content", "Meet me at spawn");
            f.addProperty("timestamp", 1755057712345L);
            out.add(make("private_message_typical", "PrivateMessagePacket",
                    PacketIds.PRIVATE_MESSAGE, reqId, false, true, null,
                    "私聊消息 S→C 完整形态：后端已填 targetId 与服务器时间戳", f, frameOf(p)));
        }
        {
            // C->S form: nil targetId (backend resolves by name), CJK/emoji content.
            PrivateMessagePacket p = new PrivateMessagePacket(UUID_B, "玩家·小明", "生存服-01",
                    "Alex", UUID_NIL, "晚上一起打末影龙吗？🐉✨", 1755057799999L);
            UUID reqId = rid(seq++);
            p.setRequestId(reqId);
            JsonObject f = new JsonObject();
            f.addProperty("senderId", UUID_B.toString());
            f.addProperty("senderName", "玩家·小明");
            f.addProperty("senderClientId", "生存服-01");
            f.addProperty("targetName", "Alex");
            f.addProperty("targetId", UUID_NIL.toString());
            f.addProperty("content", "晚上一起打末影龙吗？🐉✨");
            f.addProperty("timestamp", 1755057799999L);
            out.add(make("private_message_cjk_nil_target", "PrivateMessagePacket",
                    PacketIds.PRIVATE_MESSAGE, reqId, false, true, null,
                    "私聊消息 C→S 边界：全零 targetId（后端按名解析）+ 中文名/emoji 内容", f, frameOf(p)));
        }

        return out;
    }

    // ==================== helpers ====================

    private static Sample make(String name, String packetName, int packetId, UUID requestId,
                               boolean legacyWire, boolean reencodeExact,
                               Map<String, String> knownDrift, String description,
                               JsonObject fields, byte[] frame) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("packet", packetName);
        json.addProperty("packetId", packetId);
        json.addProperty("requestId", requestId.toString());
        json.addProperty("legacyWire", legacyWire);
        json.addProperty("reencodeExact", reencodeExact);
        json.addProperty("description", description);
        if (knownDrift != null && !knownDrift.isEmpty()) {
            JsonObject drift = new JsonObject();
            knownDrift.forEach(drift::addProperty);
            json.add("knownDrift", drift);
        }
        json.add("fields", fields);
        json.addProperty("frameHex", hex(frame));
        return new Sample(name, json, frame);
    }

    /** Encodes the full frame (VarInt length prefix + packetId + requestId + payload). */
    private static byte[] frameOf(Packet packet) {
        ByteBuf body = Unpooled.buffer();
        try {
            packet.encode(body);
            return prependLength(body);
        } finally {
            body.release();
        }
    }

    /** Hand-crafted frame for legacy wire forms (optional trailing fields absent). */
    private static byte[] legacyFrame(int packetId, UUID requestId, Consumer<ByteBuf> payload) {
        ByteBuf body = Unpooled.buffer();
        try {
            body.writeByte(packetId);
            body.writeLong(requestId.getMostSignificantBits());
            body.writeLong(requestId.getLeastSignificantBits());
            payload.accept(body);
            return prependLength(body);
        } finally {
            body.release();
        }
    }

    private static byte[] prependLength(ByteBuf body) {
        byte[] bodyBytes = new byte[body.readableBytes()];
        body.getBytes(body.readerIndex(), bodyBytes);
        ByteBuf out = Unpooled.buffer();
        try {
            VarInt.writeVarInt(out, bodyBytes.length);
            out.writeBytes(bodyBytes);
            byte[] frame = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), frame);
            return frame;
        } finally {
            out.release();
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
