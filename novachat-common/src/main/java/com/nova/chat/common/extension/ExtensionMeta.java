package com.nova.chat.common.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Metadata for a NovaChat extension.
 * This information is typically loaded from an extension.yml file.
 * 
 * <p>Example extension.yml:
 * <pre>
 * id: my-extension
 * name: My Custom Extension
 * version: 1.0.0
 * author: Developer
 * description: A custom NovaChat extension
 * main: com.example.MyExtension
 * dependencies:
 *   - other-extension
 * </pre>
 */
public class ExtensionMeta {
    
    private final String id;
    private final String name;
    private final String version;
    private final String author;
    private final String description;
    private final String main;
    private final List<String> dependencies;
    
    /**
     * Creates a new ExtensionMeta with all fields.
     * 
     * @param id unique identifier for the extension
     * @param name display name of the extension
     * @param version version string (e.g., "1.0.0")
     * @param author author of the extension
     * @param description brief description of the extension
     * @param main fully qualified class name of the main extension class
     * @param dependencies list of extension IDs this extension depends on
     */
    public ExtensionMeta(String id, String name, String version, String author, 
                         String description, String main, List<String> dependencies) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.version = Objects.requireNonNull(version, "version cannot be null");
        this.author = author != null ? author : "";
        this.description = description != null ? description : "";
        this.main = Objects.requireNonNull(main, "main cannot be null");
        this.dependencies = dependencies != null 
            ? Collections.unmodifiableList(new ArrayList<>(dependencies))
            : Collections.emptyList();
    }

    
    /**
     * Gets the unique identifier for this extension.
     * 
     * @return the extension ID
     */
    public String getId() {
        return id;
    }
    
    /**
     * Gets the display name of this extension.
     * 
     * @return the extension name
     */
    public String getName() {
        return name;
    }
    
    /**
     * Gets the version string of this extension.
     * 
     * @return the version string
     */
    public String getVersion() {
        return version;
    }
    
    /**
     * Gets the author of this extension.
     * 
     * @return the author name, or empty string if not specified
     */
    public String getAuthor() {
        return author;
    }
    
    /**
     * Gets the description of this extension.
     * 
     * @return the description, or empty string if not specified
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Gets the fully qualified class name of the main extension class.
     * 
     * @return the main class name
     */
    public String getMain() {
        return main;
    }
    
    /**
     * Gets the list of extension IDs this extension depends on.
     * 
     * @return unmodifiable list of dependency IDs
     */
    public List<String> getDependencies() {
        return dependencies;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExtensionMeta that = (ExtensionMeta) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(name, that.name) &&
               Objects.equals(version, that.version) &&
               Objects.equals(author, that.author) &&
               Objects.equals(description, that.description) &&
               Objects.equals(main, that.main) &&
               Objects.equals(dependencies, that.dependencies);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id, name, version, author, description, main, dependencies);
    }
    
    @Override
    public String toString() {
        return "ExtensionMeta{" +
               "id='" + id + '\'' +
               ", name='" + name + '\'' +
               ", version='" + version + '\'' +
               ", author='" + author + '\'' +
               ", description='" + description + '\'' +
               ", main='" + main + '\'' +
               ", dependencies=" + dependencies +
               '}';
    }
    
    /**
     * Builder for creating ExtensionMeta instances.
     */
    public static class Builder {
        private String id;
        private String name;
        private String version;
        private String author;
        private String description;
        private String main;
        private List<String> dependencies = new ArrayList<>();
        
        public Builder id(String id) {
            this.id = id;
            return this;
        }
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder version(String version) {
            this.version = version;
            return this;
        }
        
        public Builder author(String author) {
            this.author = author;
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public Builder main(String main) {
            this.main = main;
            return this;
        }
        
        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies != null ? new ArrayList<>(dependencies) : new ArrayList<>();
            return this;
        }
        
        public Builder addDependency(String dependency) {
            this.dependencies.add(dependency);
            return this;
        }
        
        /**
         * Builds the {@link ExtensionMeta} from the configured values.
         *
         * <p>No validation is performed here: required fields ({@code id},
         * {@code name}, {@code version}, {@code main}) are only null-checked
         * inside the {@link ExtensionMeta} constructor, so leaving them unset
         * will raise a {@code NullPointerException} at build time.
         *
         * @return a new ExtensionMeta instance
         */
        public ExtensionMeta build() {
            return new ExtensionMeta(id, name, version, author, description, main, dependencies);
        }
    }
    
    /**
     * Creates a new builder for ExtensionMeta.
     * 
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}
