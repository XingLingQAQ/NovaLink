package com.nova.chat.proxy.config;

import com.nova.chat.client.network.ClientConnectionConfig;

import java.util.Map;

/**
 * Read-only view of the proxy configuration consumed by the shared proxy core.
 *
 * <p>{@link ProxyConfig} is the canonical implementation. Each platform shell
 * also exposes its own {@code NovaChatConfig} (which owns the dialect-specific
 * template installation/upgrade bookkeeping) as a {@code ProxyConfigView}, so the
 * core never depends on a shell type and the shells keep their existing
 * {@code getX()} surface for their own startup code.
 */
public interface ProxyConfigView {

    /**
     * @return the backend host
     */
    String getBackendHost();

    /**
     * @return the backend port (1..65535)
     */
    int getBackendPort();

    /**
     * @return the backend username (may be empty)
     */
    String getUsername();

    /**
     * @return the backend password (may be empty)
     */
    String getPassword();

    /**
     * @return the initial reconnect delay in seconds
     */
    int getReconnectDelay();

    /**
     * @return whether proxy chat is replaced by the channel system
     */
    boolean isReplaceVanilla();

    /**
     * @return the default channel new players join
     */
    String getDefaultChannel();

    /**
     * @return the configured default locale (e.g. {@code "zh_CN"})
     */
    String getLocale();

    /**
     * @return the prefix-routing map (message prefix → channel id); empty when
     *         the feature is disabled
     */
    Map<String, String> getChannelPrefixes();

    /**
     * @return the message prefix shown before system/error/success text
     */
    String getPrefix();

    /**
     * @return the error format template
     */
    String getErrorFormat();

    /**
     * @return the success format template
     */
    String getSuccessFormat();

    /**
     * @return the fallback channel format template
     */
    String getDefaultFormat();

    /**
     * @return the per-channel format templates
     */
    Map<String, String> getChannelFormats();

    /**
     * @param channelId the channel id
     * @return the channel's format template, or the default format
     */
    String getChannelFormat(String channelId);

    /**
     * @return whether debug logging is enabled
     */
    boolean isDebug();

    /**
     * Maps the settings onto the shared {@link ClientConnectionConfig}.
     *
     * @return the connection config for the shared network client
     */
    default ClientConnectionConfig toClientConnectionConfig() {
        return ClientConnectionConfig.builder()
                .host(getBackendHost())
                .port(getBackendPort())
                .username(getUsername())
                .password(getPassword())
                .initialReconnectDelaySeconds(getReconnectDelay())
                .build();
    }
}
