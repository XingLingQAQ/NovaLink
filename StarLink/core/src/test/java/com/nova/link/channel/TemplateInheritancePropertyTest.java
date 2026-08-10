package com.nova.link.channel;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Template Inheritance.
 * 
 * **Feature: starchat-starlink, Property 17: Template Inheritance**
 * 
 * For any channel using a template, the channel should inherit all template 
 * properties except those explicitly overridden.
 * 
 * **Validates: Requirements 5.5**
 */
public class TemplateInheritancePropertyTest {

    /**
     * **Feature: starchat-starlink, Property 17: Template Inheritance**
     * 
     * For any channel using a template, the channel should inherit all template 
     * properties except those explicitly overridden.
     * 
     * This test verifies that when a channel uses a template:
     * 1. All template properties are inherited when not overridden
     * 2. Explicit overrides take precedence over template values
     * 
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 100)
    void channelInheritsAllTemplatePropertiesWhenNotOverridden(
            @ForAll @StringLength(min = 1, max = 20) String templateId,
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @StringLength(min = 1, max = 30) String templateDisplayName,
            @ForAll @StringLength(min = 1, max = 30) String templatePermission,
            @ForAll @IntRange(min = 1, max = 1000) int templateMaxCapacity
    ) {
        // Setup template manager
        TemplateManager templateManager = new TemplateManager();
        ChannelManager channelManager = new ChannelManager();
        ServerChannelLoader loader = new ServerChannelLoader(channelManager, templateManager);
        
        // Create and register a template with all properties set
        ChannelTemplate template = new ChannelTemplate(templateId);
        template.setDisplayName(templateDisplayName);
        template.setScope(ChannelScope.SERVER);
        template.setPermission(templatePermission);
        template.setMaxCapacity(templateMaxCapacity);
        templateManager.registerTemplate(template);
        
        // Create channel config that uses the template WITHOUT any overrides
        Map<String, Map<String, Object>> channelsConfig = new HashMap<>();
        Map<String, Object> channelConfig = new HashMap<>();
        channelConfig.put("use_template", templateId);
        channelsConfig.put(channelId, channelConfig);
        
        // Load the channel
        List<Channel> channels = loader.loadServerChannels(clientId, channelsConfig);
        
        // PROPERTY: Channel should inherit ALL template properties
        assertThat(channels).hasSize(1);
        Channel channel = channels.get(0);
        
        assertThat(channel.getDisplayName())
                .as("Channel should inherit display name from template")
                .isEqualTo(templateDisplayName);
        
        assertThat(channel.getScope())
                .as("Channel should inherit scope from template")
                .isEqualTo(ChannelScope.SERVER);
        
        assertThat(channel.getPermission())
                .as("Channel should inherit permission from template")
                .isEqualTo(templatePermission);
        
        assertThat(channel.getMaxCapacity())
                .as("Channel should inherit max capacity from template")
                .isEqualTo(templateMaxCapacity);
    }

    /**
     * Property 17 (continued): Explicit overrides take precedence over template values.
     * 
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 100)
    void explicitOverridesTakePrecedenceOverTemplateValues(
            @ForAll @StringLength(min = 1, max = 20) String templateId,
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @StringLength(min = 1, max = 30) String templateDisplayName,
            @ForAll @StringLength(min = 1, max = 30) String overrideDisplayName,
            @ForAll @IntRange(min = 1, max = 500) int templateMaxCapacity,
            @ForAll @IntRange(min = 501, max = 1000) int overrideMaxCapacity
    ) {
        // Ensure override values are different from template values
        Assume.that(!templateDisplayName.equals(overrideDisplayName));
        
        // Setup
        TemplateManager templateManager = new TemplateManager();
        ChannelManager channelManager = new ChannelManager();
        ServerChannelLoader loader = new ServerChannelLoader(channelManager, templateManager);
        
        // Create and register a template
        ChannelTemplate template = new ChannelTemplate(templateId);
        template.setDisplayName(templateDisplayName);
        template.setScope(ChannelScope.SERVER);
        template.setMaxCapacity(templateMaxCapacity);
        templateManager.registerTemplate(template);
        
        // Create channel config that uses the template WITH overrides
        Map<String, Map<String, Object>> channelsConfig = new HashMap<>();
        Map<String, Object> channelConfig = new HashMap<>();
        channelConfig.put("use_template", templateId);
        channelConfig.put("display_name", overrideDisplayName);  // Override display name
        channelConfig.put("max_capacity", overrideMaxCapacity);  // Override max capacity
        channelsConfig.put(channelId, channelConfig);
        
        // Load the channel
        List<Channel> channels = loader.loadServerChannels(clientId, channelsConfig);
        
        // PROPERTY: Overridden values should take precedence
        assertThat(channels).hasSize(1);
        Channel channel = channels.get(0);
        
        assertThat(channel.getDisplayName())
                .as("Explicit display name should override template value")
                .isEqualTo(overrideDisplayName);
        
        assertThat(channel.getMaxCapacity())
                .as("Explicit max capacity should override template value")
                .isEqualTo(overrideMaxCapacity);
        
        // PROPERTY: Non-overridden values should still come from template
        assertThat(channel.getScope())
                .as("Non-overridden scope should come from template")
                .isEqualTo(ChannelScope.SERVER);
    }

    /**
     * Property 17 (continued): Partial overrides work correctly.
     * Only the explicitly overridden properties should differ from template.
     * 
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 100)
    void partialOverridesOnlyAffectSpecifiedProperties(
            @ForAll @StringLength(min = 1, max = 20) String templateId,
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @StringLength(min = 1, max = 30) String templateDisplayName,
            @ForAll @StringLength(min = 1, max = 30) String templatePermission,
            @ForAll @IntRange(min = 1, max = 1000) int templateMaxCapacity,
            @ForAll @StringLength(min = 1, max = 30) String overridePermission
    ) {
        // Ensure override is different
        Assume.that(!templatePermission.equals(overridePermission));
        
        // Setup
        TemplateManager templateManager = new TemplateManager();
        ChannelManager channelManager = new ChannelManager();
        ServerChannelLoader loader = new ServerChannelLoader(channelManager, templateManager);
        
        // Create and register a template with all properties
        ChannelTemplate template = new ChannelTemplate(templateId);
        template.setDisplayName(templateDisplayName);
        template.setScope(ChannelScope.SERVER);
        template.setPermission(templatePermission);
        template.setMaxCapacity(templateMaxCapacity);
        templateManager.registerTemplate(template);
        
        // Create channel config that overrides ONLY permission
        Map<String, Map<String, Object>> channelsConfig = new HashMap<>();
        Map<String, Object> channelConfig = new HashMap<>();
        channelConfig.put("use_template", templateId);
        channelConfig.put("permission", overridePermission);  // Only override permission
        channelsConfig.put(channelId, channelConfig);
        
        // Load the channel
        List<Channel> channels = loader.loadServerChannels(clientId, channelsConfig);
        
        assertThat(channels).hasSize(1);
        Channel channel = channels.get(0);
        
        // PROPERTY: Only permission should be overridden
        assertThat(channel.getPermission())
                .as("Permission should be overridden")
                .isEqualTo(overridePermission);
        
        // PROPERTY: All other properties should come from template
        assertThat(channel.getDisplayName())
                .as("Display name should come from template (not overridden)")
                .isEqualTo(templateDisplayName);
        
        assertThat(channel.getMaxCapacity())
                .as("Max capacity should come from template (not overridden)")
                .isEqualTo(templateMaxCapacity);
        
        assertThat(channel.getScope())
                .as("Scope should come from template (not overridden)")
                .isEqualTo(ChannelScope.SERVER);
    }

    /**
     * Property 17 (continued): Template with allowed_worlds is inherited correctly.
     * 
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 100)
    void templateAllowedWorldsAreInherited(
            @ForAll @StringLength(min = 1, max = 20) String templateId,
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @Size(min = 1, max = 5) List<@StringLength(min = 1, max = 20) String> templateWorlds
    ) {
        // Ensure unique worlds
        Set<String> uniqueWorlds = new HashSet<>(templateWorlds);
        Assume.that(uniqueWorlds.size() >= 1);
        List<String> worldsList = new ArrayList<>(uniqueWorlds);
        
        // Setup
        TemplateManager templateManager = new TemplateManager();
        ChannelManager channelManager = new ChannelManager();
        ServerChannelLoader loader = new ServerChannelLoader(channelManager, templateManager);
        
        // Create and register a template with allowed_worlds
        ChannelTemplate template = new ChannelTemplate(templateId);
        template.setScope(ChannelScope.SERVER);
        template.setAllowedWorlds(worldsList);
        templateManager.registerTemplate(template);
        
        // Create channel config that uses the template
        Map<String, Map<String, Object>> channelsConfig = new HashMap<>();
        Map<String, Object> channelConfig = new HashMap<>();
        channelConfig.put("use_template", templateId);
        channelsConfig.put(channelId, channelConfig);
        
        // Load the channel
        List<Channel> channels = loader.loadServerChannels(clientId, channelsConfig);
        
        assertThat(channels).hasSize(1);
        Channel channel = channels.get(0);
        
        // PROPERTY: Allowed worlds should be inherited from template
        assertThat(channel.getAllowedWorlds())
                .as("Allowed worlds should be inherited from template")
                .containsExactlyInAnyOrderElementsOf(worldsList);
        
        // PROPERTY: World filter should be active
        assertThat(channel.hasWorldFilter())
                .as("Channel should have world filter when template has allowed_worlds")
                .isTrue();
        
        // PROPERTY: Each world in template should be allowed
        for (String world : worldsList) {
            assertThat(channel.isWorldAllowed(world))
                    .as("World '%s' from template should be allowed", world)
                    .isTrue();
        }
    }

    /**
     * Property 17 (continued): Channel without template uses explicit values only.
     * 
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 100)
    void channelWithoutTemplateUsesExplicitValuesOnly(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @StringLength(min = 1, max = 30) String displayName,
            @ForAll @IntRange(min = 1, max = 1000) int maxCapacity
    ) {
        // Setup
        TemplateManager templateManager = new TemplateManager();
        ChannelManager channelManager = new ChannelManager();
        ServerChannelLoader loader = new ServerChannelLoader(channelManager, templateManager);
        
        // Create channel config WITHOUT using a template
        Map<String, Map<String, Object>> channelsConfig = new HashMap<>();
        Map<String, Object> channelConfig = new HashMap<>();
        channelConfig.put("display_name", displayName);
        channelConfig.put("max_capacity", maxCapacity);
        // No use_template specified
        channelsConfig.put(channelId, channelConfig);
        
        // Load the channel
        List<Channel> channels = loader.loadServerChannels(clientId, channelsConfig);
        
        assertThat(channels).hasSize(1);
        Channel channel = channels.get(0);
        
        // PROPERTY: Channel should use explicit values
        assertThat(channel.getDisplayName())
                .as("Channel should use explicit display name")
                .isEqualTo(displayName);
        
        assertThat(channel.getMaxCapacity())
                .as("Channel should use explicit max capacity")
                .isEqualTo(maxCapacity);
        
        // PROPERTY: Scope should default to SERVER for server channels
        assertThat(channel.getScope())
                .as("Scope should default to SERVER")
                .isEqualTo(ChannelScope.SERVER);
    }

    /**
     * Property 17 (continued): Missing template is handled gracefully.
     * Channel should still be created with explicit values when template doesn't exist.
     * 
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 100)
    void missingTemplateIsHandledGracefully(
            @ForAll @StringLength(min = 1, max = 20) String nonExistentTemplateId,
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @StringLength(min = 1, max = 30) String displayName
    ) {
        // Setup - no templates registered
        TemplateManager templateManager = new TemplateManager();
        ChannelManager channelManager = new ChannelManager();
        ServerChannelLoader loader = new ServerChannelLoader(channelManager, templateManager);
        
        // Create channel config that references a non-existent template
        Map<String, Map<String, Object>> channelsConfig = new HashMap<>();
        Map<String, Object> channelConfig = new HashMap<>();
        channelConfig.put("use_template", nonExistentTemplateId);
        channelConfig.put("display_name", displayName);  // Explicit value as fallback
        channelsConfig.put(channelId, channelConfig);
        
        // Load the channel - should not throw
        List<Channel> channels = loader.loadServerChannels(clientId, channelsConfig);
        
        // PROPERTY: Channel should still be created
        assertThat(channels).hasSize(1);
        Channel channel = channels.get(0);
        
        // PROPERTY: Explicit values should be used
        assertThat(channel.getDisplayName())
                .as("Channel should use explicit display name when template is missing")
                .isEqualTo(displayName);
        
        // PROPERTY: Scope should default to SERVER
        assertThat(channel.getScope())
                .as("Scope should default to SERVER when template is missing")
                .isEqualTo(ChannelScope.SERVER);
    }
}
