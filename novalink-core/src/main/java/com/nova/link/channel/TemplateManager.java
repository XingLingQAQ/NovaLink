package com.nova.link.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages channel templates and provides template inheritance functionality.
 * Templates allow channels to inherit default values, reducing configuration duplication.
 * 
 * Requirements: 5.5 - Support channel templates with override capability
 */
public class TemplateManager {

    private static final Logger logger = LoggerFactory.getLogger(TemplateManager.class);

    /** All templates indexed by ID */
    private final Map<String, ChannelTemplate> templates;

    public TemplateManager() {
        this.templates = new ConcurrentHashMap<>();
    }

    /**
     * Registers a template.
     *
     * @param template the template to register
     * @throws IllegalArgumentException if a template with the same ID already exists
     */
    public void registerTemplate(ChannelTemplate template) {
        Objects.requireNonNull(template, "Template cannot be null");
        
        if (templates.containsKey(template.getId())) {
            throw new IllegalArgumentException("Template with ID '" + template.getId() + "' already exists");
        }
        
        templates.put(template.getId(), template);
        logger.info("Registered template: {}", template.getId());
    }

    /**
     * Gets a template by ID.
     *
     * @param templateId the template ID
     * @return the template, or null if not found
     */
    public ChannelTemplate getTemplate(String templateId) {
        return templates.get(templateId);
    }

    /**
     * Checks if a template exists.
     *
     * @param templateId the template ID
     * @return true if the template exists
     */
    public boolean templateExists(String templateId) {
        return templates.containsKey(templateId);
    }

    /**
     * Gets all registered templates.
     *
     * @return unmodifiable collection of all templates
     */
    public Collection<ChannelTemplate> getAllTemplates() {
        return Collections.unmodifiableCollection(templates.values());
    }

    /**
     * Removes a template by ID.
     *
     * @param templateId the template ID
     * @return true if the template was removed
     */
    public boolean removeTemplate(String templateId) {
        ChannelTemplate removed = templates.remove(templateId);
        if (removed != null) {
            logger.info("Removed template: {}", templateId);
            return true;
        }
        return false;
    }

    /**
     * Loads templates from a configuration map.
     * 
     * Expected format:
     * <pre>
     * templates:
     *   standard_local:
     *     display_name: "本地"
     *     scope: SERVER
     *     max_capacity: 100
     * </pre>
     *
     * @param templatesConfig map of template ID to template configuration
     * @return list of loaded templates
     */
    public List<ChannelTemplate> loadTemplates(Map<String, Map<String, Object>> templatesConfig) {
        List<ChannelTemplate> loadedTemplates = new ArrayList<>();
        
        if (templatesConfig == null || templatesConfig.isEmpty()) {
            logger.info("No templates configured");
            return loadedTemplates;
        }
        
        for (Map.Entry<String, Map<String, Object>> entry : templatesConfig.entrySet()) {
            String templateId = entry.getKey();
            Map<String, Object> config = entry.getValue();
            
            try {
                ChannelTemplate template = parseTemplate(templateId, config);
                registerTemplate(template);
                loadedTemplates.add(template);
                logger.info("Loaded template: {} (scope={})", templateId, template.getScope());
            } catch (Exception e) {
                logger.error("Failed to load template '{}': {}", templateId, e.getMessage());
            }
        }
        
        logger.info("Loaded {} template(s)", loadedTemplates.size());
        return loadedTemplates;
    }

    /**
     * Parses a template from a configuration map.
     *
     * @param templateId the template ID
     * @param config the configuration map
     * @return the parsed template
     */
    private ChannelTemplate parseTemplate(String templateId, Map<String, Object> config) {
        if (config == null) {
            config = Collections.emptyMap();
        }
        
        ChannelTemplate template = new ChannelTemplate(templateId);
        
        // Parse display name
        Object displayName = config.get("display_name");
        if (displayName instanceof String) {
            template.setDisplayName((String) displayName);
        }
        
        // Parse scope
        Object scopeValue = config.get("scope");
        if (scopeValue instanceof String) {
            try {
                template.setScope(ChannelScope.valueOf(((String) scopeValue).toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid scope '{}' in template '{}', ignoring", scopeValue, templateId);
            }
        }
        
        // Parse permission
        Object permission = config.get("permission");
        if (permission instanceof String) {
            template.setPermission((String) permission);
        }
        
        // Parse max capacity
        Object maxCapacity = config.get("max_capacity");
        if (maxCapacity instanceof Number) {
            template.setMaxCapacity(((Number) maxCapacity).intValue());
        } else if (maxCapacity instanceof String) {
            try {
                template.setMaxCapacity(Integer.parseInt((String) maxCapacity));
            } catch (NumberFormatException e) {
                logger.warn("Invalid max_capacity '{}' in template '{}', ignoring", maxCapacity, templateId);
            }
        }
        
        // Parse allowed worlds
        Object allowedWorlds = config.get("allowed_worlds");
        if (allowedWorlds instanceof List) {
            List<String> worlds = new ArrayList<>();
            for (Object world : (List<?>) allowedWorlds) {
                if (world instanceof String) {
                    worlds.add((String) world);
                }
            }
            template.setAllowedWorlds(worlds);
        }
        
        return template;
    }

    /**
     * Applies a template to a channel configuration builder.
     * Template values are applied first, then overrides are applied on top.
     *
     * @param templateId the template ID to apply
     * @param builder the builder to apply template to
     * @param overrides map of property overrides
     * @return the builder with template applied, or unchanged if template not found
     */
    public ChannelConfig.Builder applyTemplate(String templateId, ChannelConfig.Builder builder, 
                                                Map<String, Object> overrides) {
        ChannelTemplate template = templates.get(templateId);
        if (template == null) {
            logger.warn("Template '{}' not found, skipping template application", templateId);
            return builder;
        }
        
        return template.applyTo(builder, overrides);
    }

    /**
     * Reloads templates from configuration.
     * Removes existing templates and loads new ones.
     *
     * @param templatesConfig the new configuration
     * @return list of newly loaded templates
     */
    public List<ChannelTemplate> reloadTemplates(Map<String, Map<String, Object>> templatesConfig) {
        templates.clear();
        logger.info("Cleared all templates for reload");
        return loadTemplates(templatesConfig);
    }

    /**
     * Clears all templates. Used for testing.
     */
    public void clear() {
        templates.clear();
        logger.info("Cleared all templates");
    }

    /**
     * Gets the number of registered templates.
     *
     * @return template count
     */
    public int getTemplateCount() {
        return templates.size();
    }
}
