package com.nova.chat.common.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROTO-002 / PROTO-003 contract tests for {@link ProtocolLimits}.
 *
 * <p>Pins the numeric values that the Java frame codec and every packet
 * field-reader reference, and asserts the invariant the audit doc requires:
 * the dedicated ConfigSync budget is strictly under the frame ceiling, and
 * no per-field limit can reach the frame ceiling (otherwise a single field
 * could legitimately consume the whole frame, defeating PROTO-003).
 */
@DisplayName("ProtocolLimits constants and invariants")
class ProtocolLimitsTest {

    @Test
    @DisplayName("frame ceiling is 4 MiB (PROTO-002 unified value)")
    void maxFrameLengthIs4MiB() {
        assertThat(ProtocolLimits.MAX_FRAME_LENGTH).isEqualTo(4 * 1024 * 1024);
    }

    @Test
    @DisplayName("ConfigSync JSON budget is 2 MiB")
    void maxConfigSyncJsonIs2MiB() {
        assertThat(ProtocolLimits.MAX_CONFIG_SYNC_JSON).isEqualTo(2 * 1024 * 1024);
    }

    @Test
    @DisplayName("ConfigSync budget is strictly under the frame ceiling")
    void configSyncBudgetUnderFrameCeiling() {
        assertThat(ProtocolLimits.MAX_CONFIG_SYNC_JSON).isLessThan(ProtocolLimits.MAX_FRAME_LENGTH);
    }

    @Test
    @DisplayName("identifier fields are 64 bytes")
    void identifierLimits() {
        assertThat(ProtocolLimits.MAX_CHANNEL_ID).isEqualTo(64);
        assertThat(ProtocolLimits.MAX_CLIENT_ID).isEqualTo(64);
        assertThat(ProtocolLimits.MAX_SENDER_NAME).isEqualTo(64);
        assertThat(ProtocolLimits.MAX_TARGET_NAME).isEqualTo(64);
        assertThat(ProtocolLimits.MAX_NONCE).isEqualTo(64);
        assertThat(ProtocolLimits.MAX_SERVER_VERSION).isEqualTo(64);
    }

    @Test
    @DisplayName("error code is 64, error/message/preview fields are 256")
    void errorFields() {
        assertThat(ProtocolLimits.MAX_ERROR_CODE).isEqualTo(64);
        assertThat(ProtocolLimits.MAX_ERROR_MESSAGE).isEqualTo(256);
        assertThat(ProtocolLimits.MAX_MESSAGE_PREVIEW).isEqualTo(256);
        assertThat(ProtocolLimits.MAX_CHANNEL_PASSWORD).isEqualTo(256);
        assertThat(ProtocolLimits.MAX_PASSWORD_HASH).isEqualTo(256);
    }

    @Test
    @DisplayName("title/subtitle are 512, hmac is 128")
    void displayAndAuthFields() {
        assertThat(ProtocolLimits.MAX_TITLE).isEqualTo(512);
        assertThat(ProtocolLimits.MAX_SUBTITLE).isEqualTo(512);
        assertThat(ProtocolLimits.MAX_HMAC).isEqualTo(128);
    }

    @Test
    @DisplayName("message content (chat + private) is 2048")
    void messageContentLimit() {
        assertThat(ProtocolLimits.MAX_MESSAGE_CONTENT).isEqualTo(2048);
    }

    @Test
    @DisplayName("JSON payload fields are 8192")
    void jsonFields() {
        assertThat(ProtocolLimits.MAX_ITEM_JSON).isEqualTo(8192);
        assertThat(ProtocolLimits.MAX_ACTION_JSON).isEqualTo(8192);
    }

    @Test
    @DisplayName("metadata key/value mirror ChatMessagePacket precedent")
    void metadataFields() {
        assertThat(ProtocolLimits.MAX_METADATA_KEY).isEqualTo(128);
        assertThat(ProtocolLimits.MAX_METADATA_VALUE).isEqualTo(512);
    }

    @Test
    @DisplayName("every per-field limit is <= the frame ceiling")
    void allFieldsUnderFrameCeiling() {
        int[] allFieldLimits = {
                ProtocolLimits.MAX_CONFIG_SYNC_JSON,
                ProtocolLimits.MAX_CHANNEL_ID,
                ProtocolLimits.MAX_CLIENT_ID,
                ProtocolLimits.MAX_SENDER_NAME,
                ProtocolLimits.MAX_TARGET_NAME,
                ProtocolLimits.MAX_ERROR_CODE,
                ProtocolLimits.MAX_ERROR_MESSAGE,
                ProtocolLimits.MAX_TITLE,
                ProtocolLimits.MAX_SUBTITLE,
                ProtocolLimits.MAX_MESSAGE_PREVIEW,
                ProtocolLimits.MAX_MESSAGE_CONTENT,
                ProtocolLimits.MAX_PASSWORD_HASH,
                ProtocolLimits.MAX_HMAC,
                ProtocolLimits.MAX_NONCE,
                ProtocolLimits.MAX_SERVER_VERSION,
                ProtocolLimits.MAX_CHANNEL_PASSWORD,
                ProtocolLimits.MAX_ITEM_JSON,
                ProtocolLimits.MAX_ACTION_JSON,
                ProtocolLimits.MAX_METADATA_KEY,
                ProtocolLimits.MAX_METADATA_VALUE,
        };
        for (int limit : allFieldLimits) {
            assertThat(limit)
                    .as("field limit %d must not exceed MAX_FRAME_LENGTH", limit)
                    .isLessThanOrEqualTo(ProtocolLimits.MAX_FRAME_LENGTH);
        }
    }
}
