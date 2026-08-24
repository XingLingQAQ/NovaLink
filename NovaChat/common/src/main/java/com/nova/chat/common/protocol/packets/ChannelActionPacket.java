package com.nova.chat.common.protocol.packets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Channel action packet for channel operations (join, leave, create, delete, etc.).
 * 
 * Packet ID: 0x04
 * Direction: Client → Server
 */
public class ChannelActionPacket extends Packet {

    /** The action to perform */
    private ChannelAction action;
    
    /** Target channel ID */
    private String channelId;
    
    /** Password (for private channels) */
    private String password;
    
    /** Extra data for the action */
    private Map<String, String> extra;

    public ChannelActionPacket() {
        super();
        this.extra = new HashMap<>();
    }

    public ChannelActionPacket(UUID requestId) {
        super(requestId);
        this.extra = new HashMap<>();
    }

    public ChannelActionPacket(ChannelAction action, String channelId) {
        super();
        this.action = action;
        this.channelId = channelId;
        this.password = "";
        this.extra = new HashMap<>();
    }

    public ChannelActionPacket(ChannelAction action, String channelId, String password) {
        super();
        this.action = action;
        this.channelId = channelId;
        this.password = password != null ? password : "";
        this.extra = new HashMap<>();
    }

    @Override
    public int getPacketId() {
        return PacketIds.CHANNEL_ACTION;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(action.getId());
        PacketBuffer.writeString(buf, channelId);
        PacketBuffer.writeString(buf, password);
        
        // Write extra map
        PacketBuffer.writeVarInt(buf, extra.size());
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            PacketBuffer.writeString(buf, entry.getKey());
            PacketBuffer.writeString(buf, entry.getValue());
        }
    }


    @Override
    public void read(ByteBuf buf) {
        action = ChannelAction.fromId(buf.readByte());
        // PROTO-003: bound each field so a single oversized string cannot
        // approach the 4 MiB frame ceiling.
        channelId = PacketBuffer.readString(buf, ProtocolLimits.MAX_CHANNEL_ID);
        password = PacketBuffer.readString(buf, ProtocolLimits.MAX_CHANNEL_PASSWORD);

        // Read extra map (optional / legacy-compatible).
        if (!buf.isReadable()) {
            extra = new HashMap<>();
            return;
        }

        int mark = buf.readerIndex();
        try {
            int size = PacketBuffer.readVarInt(buf);
            // Heuristic guard: legacy implementations sometimes used a JSON string here (length can be large).
            if (size < 0 || size > 64) {
                throw new IllegalArgumentException("extra map size out of range: " + size);
            }

            extra = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                String key = PacketBuffer.readString(buf, ProtocolLimits.MAX_METADATA_KEY);
                String value = PacketBuffer.readString(buf, ProtocolLimits.MAX_METADATA_VALUE);
                extra.put(key, value);
            }
            return;
        } catch (Exception ignored) {
            buf.readerIndex(mark);
        }

        // Legacy fallback: extra as a single JSON string (object), try to parse into map.
        try {
            String json = PacketBuffer.readString(buf, ProtocolLimits.MAX_ACTION_JSON);
            extra = parseJsonToMap(json);
        } catch (Exception e) {
            extra = new HashMap<>();
        }
    }

    private Map<String, String> parseJsonToMap(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isBlank()) {
            return map;
        }

        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return map;
            }

            JsonObject obj = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if (key == null) {
                    continue;
                }
                if (value == null || value.isJsonNull()) {
                    map.put(key, "");
                } else if (value.isJsonPrimitive()) {
                    map.put(key, value.getAsString());
                } else {
                    map.put(key, value.toString());
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }

        return map;
    }

    // Getters and setters

    public ChannelAction getAction() {
        return action;
    }

    public void setAction(ChannelAction action) {
        this.action = action;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password != null ? password : "";
    }

    public Map<String, String> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, String> extra) {
        this.extra = extra != null ? extra : new HashMap<>();
    }

    public void addExtra(String key, String value) {
        this.extra.put(key, value);
    }

    public String getExtra(String key) {
        return this.extra.get(key);
    }

    @Override
    public String toString() {
        return "ChannelActionPacket{" +
                "action=" + action +
                ", channelId='" + channelId + '\'' +
                ", password='" + (password.isEmpty() ? "<none>" : "<hidden>") + '\'' +
                ", extra=" + extra +
                '}';
    }
}
