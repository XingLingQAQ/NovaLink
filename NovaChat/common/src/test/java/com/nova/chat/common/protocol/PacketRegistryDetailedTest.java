package com.nova.chat.common.protocol;

import com.nova.chat.common.protocol.packets.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
// ChannelAction / AdminAction live in the same protocol package.
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Detailed PacketRegistry / PacketIds consistency suite.
 *
 * <p>Defines the "core" registered set that {@link NovaProtocol#createRegistry()}
 * must expose, and verifies:
 * <ul>
 *   <li>every registered id round-trips via create + getPacketId</li>
 *   <li>no duplicate ids are accepted</li>
 *   <li>unknown ids are handled safely (null / -1 / isRegistered=false)</li>
 *   <li>PacketIds core constants match the registered set</li>
 *   <li>orphan reserved ids are defined but intentionally unregistered</li>
 * </ul>
 */
@DisplayName("PacketRegistry detailed consistency")
class PacketRegistryDetailedTest {

    /**
     * Core packet IDs that MUST be registered by {@link NovaProtocol#createRegistry()}.
     * Distinct numeric values only (TITLE_MESSAGE is an alias of TITLE and is not listed).
     */
    static final int[] CORE_PACKET_IDS = {
            PacketIds.HANDSHAKE,              // 0x01
            PacketIds.HANDSHAKE_RESPONSE,     // 0x02
            PacketIds.CHAT_MESSAGE,           // 0x03
            PacketIds.CHANNEL_ACTION,         // 0x04
            PacketIds.CHANNEL_ACTION_RESPONSE,// 0x05
            PacketIds.CONFIG_SYNC,            // 0x06
            PacketIds.KEEP_ALIVE,             // 0x07
            PacketIds.TITLE,                  // 0x09
            PacketIds.ADMIN_ACTION,           // 0x0B
            PacketIds.ADMIN_ACTION_RESPONSE,  // 0x0C
            PacketIds.ITEM_DISPLAY,           // 0x10
            PacketIds.MENTION                 // 0x12
    };

    /**
     * Reserved orphan IDs: present in PacketIds for protocol stability but
     * intentionally NOT registered until a Java Packet implementation exists.
     */
    static final int[] ORPHAN_PACKET_IDS = {
            PacketIds.PLAYER_STATE,         // 0x08
            PacketIds.ANNOUNCEMENT,         // 0x0A
            PacketIds.CHANNEL_UPDATE,      // 0x0D
            PacketIds.INVENTORY_SNAPSHOT,   // 0x11
            PacketIds.IMAGE_DISPLAY         // 0x13
    };

    /** Expected class for each core packet id. */
    static final Map<Integer, Class<? extends Packet>> CORE_PACKET_CLASSES = new LinkedHashMap<>();

    static {
        CORE_PACKET_CLASSES.put(PacketIds.HANDSHAKE, HandshakePacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.HANDSHAKE_RESPONSE, HandshakeResponsePacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.CHAT_MESSAGE, ChatMessagePacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.CHANNEL_ACTION, ChannelActionPacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.CHANNEL_ACTION_RESPONSE, ChannelActionResponsePacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.CONFIG_SYNC, ConfigSyncPacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.KEEP_ALIVE, KeepAlivePacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.TITLE, TitlePacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.ADMIN_ACTION, AdminActionPacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.ADMIN_ACTION_RESPONSE, AdminActionResponsePacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.ITEM_DISPLAY, ItemDisplayPacket.class);
        CORE_PACKET_CLASSES.put(PacketIds.MENTION, MentionPacket.class);
    }

    private PacketRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NovaProtocol.createRegistry();
    }

    static Stream<Integer> corePacketIds() {
        return Arrays.stream(CORE_PACKET_IDS).boxed();
    }

    static Stream<Integer> orphanPacketIds() {
        return Arrays.stream(ORPHAN_PACKET_IDS).boxed();
    }

    @Nested
    @DisplayName("core set registration")
    class CoreSet {

        @Test
        @DisplayName("core id list has no internal duplicates")
        void coreIdsAreUnique() {
            Set<Integer> unique = new HashSet<>();
            for (int id : CORE_PACKET_IDS) {
                assertThat(unique.add(id))
                        .as("duplicate core id 0x%02X", id)
                        .isTrue();
            }
            assertThat(unique).hasSize(CORE_PACKET_IDS.length);
        }

        @Test
        @DisplayName("orphan id list has no internal duplicates and no overlap with core")
        void orphanIdsUniqueAndDisjointFromCore() {
            Set<Integer> core = new HashSet<>();
            for (int id : CORE_PACKET_IDS) {
                core.add(id);
            }
            Set<Integer> orphans = new HashSet<>();
            for (int id : ORPHAN_PACKET_IDS) {
                assertThat(orphans.add(id))
                        .as("duplicate orphan id 0x%02X", id)
                        .isTrue();
                assertThat(core)
                        .as("orphan 0x%02X must not also be core", id)
                        .doesNotContain(id);
            }
        }

        @ParameterizedTest(name = "0x{0}")
        @MethodSource("com.nova.chat.common.protocol.PacketRegistryDetailedTest#corePacketIds")
        @DisplayName("every core id is registered")
        void coreIdRegistered(int id) {
            assertThat(registry.isRegistered(id))
                    .as("core packet id 0x%02X should be registered", id)
                    .isTrue();
        }

        @ParameterizedTest(name = "0x{0}")
        @MethodSource("com.nova.chat.common.protocol.PacketRegistryDetailedTest#orphanPacketIds")
        @DisplayName("orphan reserved ids are intentionally unregistered")
        void orphanIdNotRegistered(int id) {
            assertThat(registry.isRegistered(id))
                    .as("orphan packet id 0x%02X must NOT be registered", id)
                    .isFalse();
            assertThat(registry.createPacket(id))
                    .as("orphan packet id 0x%02X factory must return null", id)
                    .isNull();
        }

        @Test
        @DisplayName("PacketIds core constants match the registered set exactly")
        void coreConstantsMatchRegisteredSet() {
            Set<Integer> expected = new LinkedHashSet<>();
            for (int id : CORE_PACKET_IDS) {
                expected.add(id);
            }

            Set<Integer> registered = new LinkedHashSet<>();
            for (int id = 0; id <= 0xFF; id++) {
                if (registry.isRegistered(id)) {
                    registered.add(id);
                }
            }

            assertThat(registered)
                    .as("registered ids must equal the defined core set")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("TITLE_MESSAGE is an alias of TITLE (same numeric id)")
        void titleMessageIsAlias() {
            assertThat(PacketIds.TITLE_MESSAGE).isEqualTo(PacketIds.TITLE);
            assertThat(registry.isRegistered(PacketIds.TITLE_MESSAGE)).isTrue();
        }
    }

    @Nested
    @DisplayName("create round-trip")
    class CreateRoundTrip {

        @ParameterizedTest(name = "0x{0}")
        @MethodSource("com.nova.chat.common.protocol.PacketRegistryDetailedTest#corePacketIds")
        @DisplayName("createPacket returns non-null instance of expected type")
        void createYieldsExpectedClass(int id) {
            Packet packet = registry.createPacket(id);
            assertThat(packet).isNotNull();
            Class<? extends Packet> expected = CORE_PACKET_CLASSES.get(id);
            assertThat(packet).isInstanceOf(expected);
        }

        @ParameterizedTest(name = "0x{0}")
        @MethodSource("com.nova.chat.common.protocol.PacketRegistryDetailedTest#corePacketIds")
        @DisplayName("created packet reports matching getPacketId()")
        void createdPacketReportsOwnId(int id) {
            Packet packet = registry.createPacket(id);
            assertThat(packet).isNotNull();
            assertThat(packet.getPacketId())
                    .as("getPacketId for factory of 0x%02X", id)
                    .isEqualTo(id);
        }

        @ParameterizedTest(name = "0x{0}")
        @MethodSource("com.nova.chat.common.protocol.PacketRegistryDetailedTest#corePacketIds")
        @DisplayName("getPacketId(class) round-trips to the registered id")
        void getPacketIdByClass(int id) {
            Class<? extends Packet> clazz = CORE_PACKET_CLASSES.get(id);
            assertThat(registry.getPacketId(clazz))
                    .as("getPacketId(%s)", clazz.getSimpleName())
                    .isEqualTo(id);
        }

        @ParameterizedTest(name = "0x{0}")
        @MethodSource("com.nova.chat.common.protocol.PacketRegistryDetailedTest#corePacketIds")
        @DisplayName("factory produces independent instances")
        void factoryProducesIndependentInstances(int id) {
            Packet a = registry.createPacket(id);
            Packet b = registry.createPacket(id);
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
            assertThat(a).isNotSameAs(b);
        }
    }

    @Nested
    @DisplayName("duplicate registration")
    class Duplicates {

        @Test
        @DisplayName("registering the same id twice throws IllegalArgumentException")
        void duplicateIdRejected() {
            PacketRegistry empty = new PacketRegistry();
            empty.register(PacketIds.HANDSHAKE, HandshakePacket.class, HandshakePacket::new);
            assertThatThrownBy(() ->
                    empty.register(PacketIds.HANDSHAKE, HandshakePacket.class, HandshakePacket::new))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already registered");
        }

        @Test
        @DisplayName("createRegistry itself does not throw (no internal duplicates)")
        void createRegistryHasNoInternalDuplicates() {
            // If NovaProtocol registered the same id twice, createRegistry would throw.
            PacketRegistry r = NovaProtocol.createRegistry();
            assertThat(r).isNotNull();
            for (int id : CORE_PACKET_IDS) {
                assertThat(r.isRegistered(id)).isTrue();
            }
        }

        @Test
        @DisplayName("scanning 0x00-0xFF yields exactly the core count of registered ids")
        void noExtraOrDuplicateRegistrations() {
            long count = IntStream.rangeClosed(0, 0xFF)
                    .filter(registry::isRegistered)
                    .count();
            assertThat(count).isEqualTo(CORE_PACKET_IDS.length);
        }
    }

    @Nested
    @DisplayName("unknown id handling")
    class UnknownIds {

        @ParameterizedTest(name = "id={0}")
        @ValueSource(ints = {0x00, 0x0E, 0x0F, 0x14, 0x20, 0x7F, 0xFE, 0xFF})
        @DisplayName("isRegistered is false for unused ids")
        void isRegisteredFalse(int id) {
            assertThat(registry.isRegistered(id)).isFalse();
        }

        @ParameterizedTest(name = "id={0}")
        @ValueSource(ints = {0x00, 0x0E, 0x0F, 0x14, 0x20, 0x7F, 0xFE, 0xFF})
        @DisplayName("createPacket returns null for unused ids")
        void createReturnsNull(int id) {
            assertThat(registry.createPacket(id)).isNull();
        }

        @Test
        @DisplayName("getPacketId returns -1 for an unregistered class")
        void getPacketIdUnknownClass() {
            // Packet is abstract; use a synthetic anonymous subclass that is never registered.
            class UnregisteredPacket extends Packet {
                @Override
                public int getPacketId() {
                    return 0xEE;
                }

                @Override
                public void write(ByteBuf buf) {
                }

                @Override
                public void read(ByteBuf buf) {
                }
            }
            assertThat(registry.getPacketId(UnregisteredPacket.class)).isEqualTo(-1);
        }

        @Test
        @DisplayName("decode of unknown packet id consumes the id byte and returns null")
        void decodeUnknownIdReturnsNull() {
            ByteBuf buf = Unpooled.buffer();
            try {
                buf.writeByte(0xEE); // unknown id
                // Provide a fake UUID so a misbehaving decoder wouldn't fail on EOF first
                buf.writeLong(0L);
                buf.writeLong(0L);
                Packet decoded = registry.decode(buf);
                assertThat(decoded).isNull();
                // id byte must have been consumed
                assertThat(buf.readerIndex()).isEqualTo(1);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("orphan ids behave like unknown for create/decode")
        void orphansBehaveLikeUnknown() {
            for (int id : ORPHAN_PACKET_IDS) {
                assertThat(registry.createPacket(id)).isNull();
                assertThat(registry.isRegistered(id)).isFalse();

                ByteBuf buf = Unpooled.buffer();
                try {
                    buf.writeByte(id);
                    assertThat(registry.decode(buf)).isNull();
                } finally {
                    buf.release();
                }
            }
        }
    }

    @Nested
    @DisplayName("PacketIds field inventory")
    class PacketIdsInventory {

        @Test
        @DisplayName("every public static final int in PacketIds is either core, orphan, or TITLE_MESSAGE alias")
        void allConstantsAccountedFor() throws IllegalAccessException {
            Set<Integer> accounted = new HashSet<>();
            for (int id : CORE_PACKET_IDS) {
                accounted.add(id);
            }
            for (int id : ORPHAN_PACKET_IDS) {
                accounted.add(id);
            }
            // TITLE_MESSAGE is an alias of TITLE; already in core via TITLE.
            accounted.add(PacketIds.TITLE_MESSAGE);

            Set<Integer> discovered = new HashSet<>();
            for (Field field : PacketIds.class.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods) || !Modifier.isPublic(mods)) {
                    continue;
                }
                if (field.getType() != int.class) {
                    continue;
                }
                int value = field.getInt(null);
                discovered.add(value);
                assertThat(accounted)
                        .as("PacketIds.%s = 0x%02X must be listed as core, orphan, or alias",
                                field.getName(), value)
                        .contains(value);
            }

            // Every core + orphan constant must appear as a PacketIds field value.
            for (int id : CORE_PACKET_IDS) {
                assertThat(discovered).as("core 0x%02X must exist in PacketIds", id).contains(id);
            }
            for (int id : ORPHAN_PACKET_IDS) {
                assertThat(discovered).as("orphan 0x%02X must exist in PacketIds", id).contains(id);
            }
        }
    }

    @Nested
    @DisplayName("encode/decode smoke for every registered factory")
    class EncodeDecodeSmoke {

        /**
         * Seeds the minimum fields required for a factory-built packet to encode
         * safely. Several packet types leave enum/string fields null after the
         * no-arg constructor (e.g. ChannelActionPacket.action).
         */
        private void seedMinimalPayload(Packet packet) {
            if (packet instanceof ChannelActionPacket cap) {
                cap.setAction(ChannelAction.JOIN);
                cap.setChannelId("global");
                cap.setPassword("");
            } else if (packet instanceof AdminActionPacket aap) {
                aap.setAction(AdminAction.AUTH);
                aap.setPlayerId(java.util.UUID.randomUUID());
                aap.setPasswordHash("");
                aap.setTarget("");
            }
        }

        @ParameterizedTest(name = "0x{0}")
        @MethodSource("com.nova.chat.common.protocol.PacketRegistryDetailedTest#corePacketIds")
        @DisplayName("factory-built packet encodes and decodes without throwing")
        void defaultInstanceRoundTrip(int id) {
            Packet original = registry.createPacket(id);
            assertThat(original).isNotNull();
            seedMinimalPayload(original);

            ByteBuf buf = Unpooled.buffer();
            try {
                registry.encode(original, buf);
                Packet decoded = registry.decode(buf);
                assertThat(decoded).isNotNull();
                assertThat(decoded.getPacketId()).isEqualTo(id);
                assertThat(decoded.getClass()).isEqualTo(original.getClass());
                assertThat(decoded.getRequestId()).isEqualTo(original.getRequestId());
            } finally {
                buf.release();
            }
        }
    }
}
