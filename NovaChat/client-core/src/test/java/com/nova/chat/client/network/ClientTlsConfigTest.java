package com.nova.chat.client.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ClientTlsConfigTest {
    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void clearTlsProperties() {
        for (String key : new String[] { ClientTlsConfig.ENABLED_PROPERTY, ClientTlsConfig.CA_CERT_PROPERTY,
                ClientTlsConfig.CLIENT_CERT_PROPERTY, ClientTlsConfig.CLIENT_KEY_PROPERTY }) {
            saved.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreTlsProperties() {
        saved.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    @Test
    void defaultConnectionKeepsTlsDisabled() {
        assertThat(ClientConnectionConfig.builder().build().getTls()).isNull();
    }

    @Test
    void platformBuildersUseRuntimeTrustMaterial() {
        System.setProperty(ClientTlsConfig.ENABLED_PROPERTY, "true");
        System.setProperty(ClientTlsConfig.CA_CERT_PROPERTY, "trusted-ca.pem");
        ClientConnectionConfig config = ClientConnectionConfig.builder().build();
        assertThat(config.getTls().getCaCertFile()).isEqualTo("trusted-ca.pem");
        assertThat(config.toBuilder().build().getTls()).isEqualTo(config.getTls());
        assertThat(ClientConnectionConfig.builder().tls(new ClientTlsConfig("other.pem", null, null))
                .build().getTls().getCaCertFile()).isEqualTo("other.pem");
    }

    @Test
    void enabledWithoutPemUsesJvmTrustStore() {
        System.setProperty(ClientTlsConfig.ENABLED_PROPERTY, "true");
        assertThat(ClientConnectionConfig.builder().build().getTls()).isNotNull();
        assertThat(ClientConnectionConfig.builder().build().getTls().getCaCertFile()).isNull();
    }

    @Test
    void disabledTlsDoesNotSilentlyIgnoreConfiguredTrustMaterial() {
        System.setProperty(ClientTlsConfig.CA_CERT_PROPERTY, "trusted-ca.pem");
        assertThatThrownBy(ClientConnectionConfig::builder).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedEnableFlagFailsClosed() {
        System.setProperty(ClientTlsConfig.ENABLED_PROPERTY, "treu");
        assertThatThrownBy(ClientConnectionConfig::builder).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mutualTlsRequiresBothIdentityFiles() {
        assertThatThrownBy(() -> new ClientTlsConfig(null, "client.pem", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientTlsConfig(null, null, "client.key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ClientTlsConfig(null, "client.pem", "client.key").hasClientIdentity()).isTrue();
    }
}
