package com.nova.chat.common.extension;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parser for extension.yml metadata files.
 * 
 * <p>This class handles parsing YAML content into {@link ExtensionMeta} objects
 * and serializing {@link ExtensionMeta} back to YAML format.
 */
public class ExtensionMetaParser {
    
    private final Yaml yaml;
    
    public ExtensionMetaParser() {
        this.yaml = new Yaml();
    }
    
    /**
     * Parses extension metadata from a YAML input stream.
     * 
     * @param inputStream the input stream containing YAML content
     * @return the parsed ExtensionMeta
     * @throws ExtensionException if parsing fails or required fields are missing
     */
    public ExtensionMeta parse(InputStream inputStream) throws ExtensionException {
        try {
            Map<String, Object> data = yaml.load(inputStream);
            return parseFromMap(data);
        } catch (Exception e) {
            throw new ExtensionException("Failed to parse extension.yml", e);
        }
    }
    
    /**
     * Parses extension metadata from a YAML string.
     * 
     * @param yamlContent the YAML content as a string
     * @return the parsed ExtensionMeta
     * @throws ExtensionException if parsing fails or required fields are missing
     */
    public ExtensionMeta parse(String yamlContent) throws ExtensionException {
        try {
            Map<String, Object> data = yaml.load(yamlContent);
            return parseFromMap(data);
        } catch (Exception e) {
            throw new ExtensionException("Failed to parse extension.yml", e);
        }
    }

    
    /**
     * Parses extension metadata from a Map (typically from YAML parsing).
     * 
     * @param data the map containing extension metadata
     * @return the parsed ExtensionMeta
     * @throws ExtensionException if required fields are missing
     */
    @SuppressWarnings("unchecked")
    public ExtensionMeta parseFromMap(Map<String, Object> data) throws ExtensionException {
        if (data == null) {
            throw new ExtensionException("Extension metadata is empty");
        }
        
        String id = getRequiredString(data, "id");
        String name = getRequiredString(data, "name");
        String version = getRequiredString(data, "version");
        String main = getRequiredString(data, "main");
        
        String author = getOptionalString(data, "author", "");
        String description = getOptionalString(data, "description", "");
        
        List<String> dependencies = new ArrayList<>();
        Object depsObj = data.get("dependencies");
        if (depsObj instanceof List) {
            for (Object dep : (List<?>) depsObj) {
                if (dep != null) {
                    dependencies.add(dep.toString());
                }
            }
        }
        
        return new ExtensionMeta(id, name, version, author, description, main, dependencies);
    }
    
    /**
     * Serializes extension metadata to YAML format.
     * 
     * @param meta the extension metadata to serialize
     * @return the YAML string representation
     */
    public String toYaml(ExtensionMeta meta) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", meta.getId());
        data.put("name", meta.getName());
        data.put("version", meta.getVersion());
        if (!meta.getAuthor().isEmpty()) {
            data.put("author", meta.getAuthor());
        }
        if (!meta.getDescription().isEmpty()) {
            data.put("description", meta.getDescription());
        }
        data.put("main", meta.getMain());
        if (!meta.getDependencies().isEmpty()) {
            data.put("dependencies", new ArrayList<>(meta.getDependencies()));
        }
        
        StringWriter writer = new StringWriter();
        yaml.dump(data, writer);
        return writer.toString();
    }
    
    private String getRequiredString(Map<String, Object> data, String key) throws ExtensionException {
        Object value = data.get(key);
        if (value == null) {
            throw new ExtensionException("Missing required field: " + key);
        }
        return value.toString();
    }
    
    private String getOptionalString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
