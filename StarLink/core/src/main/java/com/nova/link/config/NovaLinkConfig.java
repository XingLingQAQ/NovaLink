package com.nova.link.config;

import com.nova.link.auth.ClientCredentials;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelTemplate;

import java.util.*;

/**
 * Main configuration class for NovaLink backend.
 * Represents the complete novalink.yml configuration structure.
 * 
 * Requirements: 20.1-20.6 - Configuration file structure
 */
public class NovaLinkConfig {

    // Server settings
    private ServerConfig server;
    
    // Database settings
    private DatabaseConfig database;
    
    // Security settings
    private SecurityConfig security;
    
    // Super admin list
    private List<SuperAdminCredentials> superAdmins;

    // Web-panel login accounts (role ADMIN / VIEWER). Optional; when absent,
    // only super-admins can log into the panel.
    private List<PanelUserConfig> panelUsers;
    
    // Debug mode
    private boolean debug;
    
    // Global channels (GLOBAL scope)
    private Map<String, GlobalChannelConfig> globalChannels;
    
    // Channel templates
    private Map<String, ChannelTemplateConfig> templates;
    
    // Client configurations
    private List<ClientConfig> clients;

    // Feature toggles (Settings page)
    private FeatureConfig features;

    // Custom sensitive-word filter lists (panel-managed)
    private FilterConfig filter;

    public NovaLinkConfig() {
        this.server = new ServerConfig();
        this.database = new DatabaseConfig();
        this.security = new SecurityConfig();
        this.superAdmins = new ArrayList<>();
        this.panelUsers = new ArrayList<>();
        this.debug = false;
        this.globalChannels = new LinkedHashMap<>();
        this.templates = new LinkedHashMap<>();
        this.clients = new ArrayList<>();
        this.features = new FeatureConfig();
        this.filter = new FilterConfig();
    }

    /**
     * Creates a default configuration with sensible defaults.
     */
    public static NovaLinkConfig createDefault() {
        NovaLinkConfig config = new NovaLinkConfig();
        
        // Default server config
        config.server.setBindAddress("0.0.0.0");
        config.server.setPort(8888);
        config.server.setWebsocketPort(8889);
        config.server.setSecretKey("change-me-in-production");
        config.server.setWorkerThreads(4);
        config.server.setLocale("zh_CN");
        
        // Default database config
        config.database.setType("memory");
        
        // Default security config
        config.security.setAllowedIps(Arrays.asList("127.0.0.1"));
        config.security.setIpBanDuration(300);
        
        // Default global channel
        GlobalChannelConfig globalChannel = new GlobalChannelConfig();
        globalChannel.setDisplayName("全服");
        globalChannel.setPermission("novachat.channel.global");
        globalChannel.setMaxCapacity(1000);
        config.globalChannels.put("global", globalChannel);
        
        // Default template
        ChannelTemplateConfig template = new ChannelTemplateConfig();
        template.setDisplayName("本地");
        template.setScope("SERVER");
        template.setMaxCapacity(100);
        config.templates.put("standard_local", template);
        
        return config;
    }

    // Getters and setters

    public ServerConfig getServer() {
        return server;
    }

    public void setServer(ServerConfig server) {
        this.server = server;
    }

    public DatabaseConfig getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseConfig database) {
        this.database = database;
    }

    public SecurityConfig getSecurity() {
        return security;
    }

    public void setSecurity(SecurityConfig security) {
        this.security = security;
    }

    public List<SuperAdminCredentials> getSuperAdmins() {
        return superAdmins;
    }

    public void setSuperAdmins(List<SuperAdminCredentials> superAdmins) {
        this.superAdmins = superAdmins != null ? superAdmins : new ArrayList<>();
    }

    /**
     * @return the {@code panel-users} entries; never null (empty when the
     *         section is absent — then only super-admins can log into the panel)
     */
    public List<PanelUserConfig> getPanelUsers() {
        return panelUsers;
    }

    public void setPanelUsers(List<PanelUserConfig> panelUsers) {
        this.panelUsers = panelUsers != null ? panelUsers : new ArrayList<>();
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public Map<String, GlobalChannelConfig> getGlobalChannels() {
        return globalChannels;
    }

    public void setGlobalChannels(Map<String, GlobalChannelConfig> globalChannels) {
        this.globalChannels = globalChannels != null ? globalChannels : new LinkedHashMap<>();
    }

    public Map<String, ChannelTemplateConfig> getTemplates() {
        return templates;
    }

    public void setTemplates(Map<String, ChannelTemplateConfig> templates) {
        this.templates = templates != null ? templates : new LinkedHashMap<>();
    }

    public List<ClientConfig> getClients() {
        return clients;
    }

    public void setClients(List<ClientConfig> clients) {
        this.clients = clients != null ? clients : new ArrayList<>();
    }

    public FeatureConfig getFeatures() {
        return features;
    }

    public void setFeatures(FeatureConfig features) {
        this.features = features != null ? features : new FeatureConfig();
    }

    /**
     * @return the custom sensitive-word filter lists; never null
     */
    public FilterConfig getFilter() {
        return filter;
    }

    public void setFilter(FilterConfig filter) {
        this.filter = filter != null ? filter : new FilterConfig();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NovaLinkConfig that = (NovaLinkConfig) o;
        return debug == that.debug &&
               Objects.equals(server, that.server) &&
               Objects.equals(database, that.database) &&
               Objects.equals(security, that.security) &&
               Objects.equals(superAdmins, that.superAdmins) &&
               Objects.equals(panelUsers, that.panelUsers) &&
               Objects.equals(globalChannels, that.globalChannels) &&
               Objects.equals(templates, that.templates) &&
               Objects.equals(clients, that.clients) &&
               Objects.equals(features, that.features) &&
               Objects.equals(filter, that.filter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(server, database, security, superAdmins, panelUsers, debug,
                           globalChannels, templates, clients, features, filter);
    }
}
