package com.nova.chat.mod.config;

import com.nova.chat.client.network.ClientConnectionConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration model for NovaChat Mod
 */
public class ModConfig {
    private BackendConfig backend;
    private ChatConfig chat;
    private Map<String, String> formats;
    private boolean debug;

    public ModConfig() {
        this.backend = new BackendConfig();
        this.chat = new ChatConfig();
        this.formats = new HashMap<>();
        this.debug = false;
    }

    public BackendConfig getBackend() {
        return backend;
    }

    public void setBackend(BackendConfig backend) {
        this.backend = backend;
    }

    public ChatConfig getChat() {
        return chat;
    }

    public void setChat(ChatConfig chat) {
        this.chat = chat;
    }

    public Map<String, String> getFormats() {
        return formats;
    }

    public void setFormats(Map<String, String> formats) {
        this.formats = formats;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /**
     * Convenience accessor for the backend host.
     * @return the backend host
     */
    public String getBackendHost() {
        return backend != null ? backend.getHost() : null;
    }

    /**
     * Convenience accessor for the backend port.
     * @return the backend port
     */
    public int getBackendPort() {
        return backend != null ? backend.getPort() : ClientConnectionConfig.DEFAULT_PORT;
    }

    /**
     * Convenience accessor for the backend username (client id).
     * @return the backend username
     */
    public String getUsername() {
        return backend != null ? backend.getUsername() : null;
    }

    /**
     * Builds a {@link ClientConnectionConfig} from this mod configuration for the
     * shared {@code AbstractPlatformNetworkClient} / {@code CoreNetworkClient}.
     *
     * @return a connection config populated from the backend/chat sections
     */
    public ClientConnectionConfig toClientConnectionConfig() {
        BackendConfig b = backend != null ? backend : new BackendConfig();
        return ClientConnectionConfig.builder()
                .host(b.getHost())
                .port(b.getPort())
                .username(b.getUsername())
                .password(b.getPassword())
                .initialReconnectDelaySeconds(Math.max(1, b.getReconnectDelay()))
                .build();
    }

    /**
     * Validate the configuration
     * @return true if valid
     */
    public boolean validate() {
        return backend != null && backend.validate() &&
               chat != null && chat.validate() &&
               formats != null;
    }
    
    /**
     * Backend configuration
     */
    public static class BackendConfig {
        private String host;
        private int port;
        private String username;
        private String password;
        private int reconnectDelay;
        
        public BackendConfig() {
            this.host = "127.0.0.1";
            this.port = 8888;
            this.username = "ModServer";
            this.password = "password";
            this.reconnectDelay = 5;
        }
        
        public String getHost() {
            return host;
        }
        
        public void setHost(String host) {
            this.host = host;
        }
        
        public int getPort() {
            return port;
        }
        
        public void setPort(int port) {
            this.port = port;
        }
        
        public String getUsername() {
            return username;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getPassword() {
            return password;
        }
        
        public void setPassword(String password) {
            this.password = password;
        }
        
        public int getReconnectDelay() {
            return reconnectDelay;
        }
        
        public void setReconnectDelay(int reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
        }

        /**
         * Validates the backend configuration.
         * @return true if host is non-empty, port is in 1..65535, credentials and
         *         a positive reconnect delay are all set
         */
        public boolean validate() {
            return host != null && !host.isEmpty() &&
                   port > 0 && port <= 65535 &&
                   username != null && !username.isEmpty() &&
                   password != null && !password.isEmpty() &&
                   reconnectDelay > 0;
        }
    }
    
    /**
     * Chat configuration
     */
    public static class ChatConfig {
        private boolean replaceVanilla;
        private String defaultChannel;
        private String locale;

        public ChatConfig() {
            this.replaceVanilla = false;
            this.defaultChannel = "local";
            this.locale = "zh_CN";
        }

        public boolean isReplaceVanilla() {
            return replaceVanilla;
        }

        public void setReplaceVanilla(boolean replaceVanilla) {
            this.replaceVanilla = replaceVanilla;
        }

        public String getDefaultChannel() {
            return defaultChannel;
        }

        public void setDefaultChannel(String defaultChannel) {
            this.defaultChannel = defaultChannel;
        }

        /**
         * @return the configured default locale for shared i18n (e.g. {@code zh_CN},
         *         {@code en_US}); never null
         */
        public String getLocale() {
            return locale;
        }

        public void setLocale(String locale) {
            this.locale = locale;
        }

        /**
         * Validates the chat configuration.
         * @return true if a non-empty default channel is configured
         */
        public boolean validate() {
            return defaultChannel != null && !defaultChannel.isEmpty();
        }
    }
}
