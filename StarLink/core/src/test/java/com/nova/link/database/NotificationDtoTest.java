package com.nova.link.database;

import com.google.gson.Gson;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the RedisProvider notification JSON DTO: the recipient field
 * must survive the Gson roundtrip. Before the PANEL-014 parity fix the DTO
 * dropped {@code recipient}, so directed notifications stored in Redis came
 * back as broadcasts after a restart.
 *
 * <p>RedisProvider is exercised through its public provider surface in
 * {@code RedisProviderNotificationIntegrationTest} (live Redis, assumption
 * gated); this class tests the DTO mapping directly so the serialization
 * contract is verified even on hosts without a Redis server.
 */
class NotificationDtoTest {

    /** Reflectively instantiate the private static NotificationDto. */
    private static Object newDto(Notification n) throws Exception {
        Class<?> dtoClass = Class.forName(RedisProvider.class.getName() + "$NotificationDto");
        var ctor = dtoClass.getDeclaredConstructor(Notification.class);
        ctor.setAccessible(true);
        return ctor.newInstance(n);
    }

    /** Reflectively call NotificationDto#toNotification(). */
    private static Notification toNotification(Object dto) throws Exception {
        var method = dto.getClass().getDeclaredMethod("toNotification");
        method.setAccessible(true);
        return (Notification) method.invoke(dto);
    }

    @Test
    void directedRecipientSurvivesRoundtrip() throws Exception {
        Gson gson = new Gson();
        Notification original = new Notification(1L, "t", "m", "warning", 1234567L, false, "Alice");
        String json = gson.toJson(newDto(original));

        // The JSON payload itself must carry the recipient.
        assertThat(json).contains("\"recipient\":\"Alice\"");

        Object dto = gson.fromJson(json,
                Class.forName(RedisProvider.class.getName() + "$NotificationDto"));
        Notification restored = toNotification(dto);

        assertThat(restored.getRecipient()).isEqualTo("Alice");
        assertThat(restored.getId()).isEqualTo(1L);
        assertThat(restored.getTitle()).isEqualTo("t");
        assertThat(restored.getMessage()).isEqualTo("m");
        assertThat(restored.getLevel()).isEqualTo("warning");
        assertThat(restored.getCreatedAt()).isEqualTo(1234567L);
        assertThat(restored.isRead()).isFalse();
    }

    @Test
    void broadcastNullRecipientStaysNullAfterRoundtrip() throws Exception {
        Gson gson = new Gson();
        Notification original = new Notification(2L, "b", "bm", "info", 42L, true, null);
        String json = gson.toJson(newDto(original));
        assertThat(json).doesNotContain("recipient");

        Object dto = gson.fromJson(json,
                Class.forName(RedisProvider.class.getName() + "$NotificationDto"));
        Notification restored = toNotification(dto);

        assertThat(restored.getRecipient()).as("null recipient stays broadcast").isNull();
    }

    @Test
    void storedCaseIsPreservedForDisplay() throws Exception {
        Gson gson = new Gson();
        Notification original = new Notification(3L, "t", "m", "info", 7L, false, "MiXeDCase");
        Object dto = gson.fromJson(gson.toJson(newDto(original)),
                Class.forName(RedisProvider.class.getName() + "$NotificationDto"));

        assertThat(toNotification(dto).getRecipient())
                .as("stored case must not be lowercased (matching is done at compare time)")
                .isEqualTo("MiXeDCase");
    }
}
