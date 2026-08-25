package com.nova.link.notification;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.Notification;
import com.nova.link.websocket.WebSocketGateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ghost-suppression contract: when persistence fails, the store must NOT
 * deliver the notification over WebSocket. A live popup for an unpersisted
 * record has id=0, can never be marked read or listed, and would make the
 * realtime view permanently diverge from the REST view.
 *
 * <p>WebSocketGateway is a concrete class but is mocked with Mockito (the
 * project ships mockito-core), so no seam extraction is needed. The
 * broadcast/skip decision itself is observable via the mock's invocations.
 */
@DisplayName("NotificationStore must not deliver ghost notifications when persistence fails")
class NotificationStoreGhostSuppressionTest {

    private DatabaseProvider provider;
    private WebSocketGateway gateway;
    private NotificationStore store;

    @BeforeEach
    void setUp() {
        provider = mock(DatabaseProvider.class);
        gateway = mock(WebSocketGateway.class);
        store = new NotificationStore(provider, gateway);
    }

    @Test
    @DisplayName("broadcast path: save failure skips live delivery")
    void saveFailureSkipsBroadcast() throws Exception {
        doThrowSave();

        Notification result = store.createNotification("t", "m", "info");

        verify(gateway, never()).broadcastNotification(anyString(), anyString(), anyString());
        assertThat(result.getId()).as("unpersisted notification has no id").isZero();
    }

    @Test
    @DisplayName("directed path: save failure skips directed delivery")
    void saveFailureSkipsDirectedDelivery() throws Exception {
        doThrowSave();

        Notification result = store.createDirectedNotification("t", "m", "info", "  alice  ");

        verify(gateway, never()).sendDirectedNotification(anyString(), anyString(), anyString(), anyString());
        assertThat(result.getId()).isZero();
        assertThat(result.getRecipient())
                .as("recipient must be trimmed at write time")
                .isEqualTo("alice");
    }

    @Test
    @DisplayName("successful save still delivers over WS (regression guard)")
    void successfulSaveStillDeliversBroadcast() throws Exception {
        stampIdOnSave(7L);

        Notification result = store.createNotification("t", "m", "info");

        verify(gateway).broadcastNotification("t", "m", "info");
        assertThat(result.getId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("successful directed save delivers to the trimmed recipient")
    void successfulDirectedSaveDeliversToTrimmedRecipient() throws Exception {
        stampIdOnSave(9L);

        Notification result = store.createDirectedNotification("t", "m", "info", "alice");

        verify(gateway).sendDirectedNotification(eq("alice"), eq("t"), eq("m"), eq("info"));
        assertThat(result.getRecipient()).isEqualTo("alice");
        // Persisted object carries the trimmed recipient too.
        verify(provider).saveNotification(result);
    }

    @Test
    @DisplayName("blank recipient degrades to broadcast (null/blank means broadcast)")
    void blankRecipientFallsBackToBroadcast() throws Exception {
        stampIdOnSave(3L);

        store.createDirectedNotification("t", "m", "info", "   ");

        verify(gateway).broadcastNotification("t", "m", "info");
        verify(gateway, never()).sendDirectedNotification(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("WS delivery throwing never propagates and does not affect the result")
    void gatewayFailureIsSwallowed() throws Exception {
        stampIdOnSave(5L);
        org.mockito.Mockito.doThrow(new RuntimeException("ws down"))
                .when(gateway).sendDirectedNotification(anyString(), anyString(), anyString(), anyString());

        Notification result = store.createDirectedNotification("t", "m", "info", "alice");

        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getRecipient()).isEqualTo("alice");
    }

    private void doThrowSave() throws DatabaseException {
        org.mockito.Mockito.doThrow(new DatabaseException("disk on fire"))
                .when(provider).saveNotification(org.mockito.ArgumentMatchers.any(Notification.class));
    }

    private void stampIdOnSave(long id) throws DatabaseException {
        org.mockito.Mockito.doAnswer(inv -> {
            Notification n = inv.getArgument(0);
            java.lang.reflect.Field f = Notification.class.getDeclaredField("id");
            f.setAccessible(true);
            f.setLong(n, id);
            return null;
        }).when(provider).saveNotification(any(Notification.class));
    }
}
