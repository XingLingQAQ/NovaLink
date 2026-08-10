<?php

declare(strict_types=1);

namespace NovaChat\Extension;

/**
 * Metadata for a NovaChat extension.
 * This information is typically loaded from an extension.yml file.
 * 
 * Example extension.yml:
 * ```yaml
 * id: my-extension
 * name: My Custom Extension
 * version: 1.0.0
 * author: Developer
 * description: A custom NovaChat extension
 * main: MyExtension
 * dependencies:
 *   - other-extension
 * ```
 * 
 * Requirements: 10.1 - THE PMMP 扩展 SHALL 使用 PHP 编写并放置在 extensions 目录
 */
class ExtensionMeta {
    
    private string $id;
    private string $name;
    private string $version;
    private string $author;
    private string $description;
    private string $main;
    /** @var string[] */
    private array $dependencies;
    
    /**
     * Creates a new ExtensionMeta with all fields.
     * 
     * @param string $id unique identifier for the extension
     * @param string $name display name of the extension
     * @param string $version version string (e.g., "1.0.0")
     * @param string $author author of the extension
     * @param string $description brief description of the extension
     * @param string $main class name of the main extension class
     * @param string[] $dependencies list of extension IDs this extension depends on
     */
    public function __construct(
        string $id,
        string $name,
        string $version,
        string $author = "",
        string $description = "",
        string $main = "",
        array $dependencies = []
    ) {
        $this->id = $id;
        $this->name = $name;
        $this->version = $version;
        $this->author = $author;
        $this->description = $description;
        $this->main = $main;
        $this->dependencies = $dependencies;
    }
    
    /**
     * Gets the unique identifier for this extension.
     */
    public function getId(): string {
        return $this->id;
    }
    
    /**
     * Gets the display name of this extension.
     */
    public function getName(): string {
        return $this->name;
    }
    
    /**
     * Gets the version string of this extension.
     */
    public function getVersion(): string {
        return $this->version;
    }
    
    /**
     * Gets the author of this extension.
     */
    public function getAuthor(): string {
        return $this->author;
    }
    
    /**
     * Gets the description of this extension.
     */
    public function getDescription(): string {
        return $this->description;
    }
    
    /**
     * Gets the class name of the main extension class.
     */
    public function getMain(): string {
        return $this->main;
    }
    
    /**
     * Gets the list of extension IDs this extension depends on.
     * 
     * @return string[]
     */
    public function getDependencies(): array {
        return $this->dependencies;
    }
    
    /**
     * Converts the metadata to an array for serialization.
     * 
     * @return array<string, mixed>
     */
    public function toArray(): array {
        return [
            'id' => $this->id,
            'name' => $this->name,
            'version' => $this->version,
            'author' => $this->author,
            'description' => $this->description,
            'main' => $this->main,
            'dependencies' => $this->dependencies,
        ];
    }
    
    /**
     * Creates an ExtensionMeta from an array (e.g., parsed YAML).
     * 
     * @param array<string, mixed> $data the data array
     * @return ExtensionMeta the created metadata
     * @throws ExtensionException if required fields are missing
     */
    public static function fromArray(array $data): ExtensionMeta {
        if (!isset($data['id']) || !is_string($data['id'])) {
            throw new ExtensionException("Missing required field: id");
        }
        if (!isset($data['name']) || !is_string($data['name'])) {
            throw new ExtensionException("Missing required field: name");
        }
        if (!isset($data['version']) || !is_string($data['version'])) {
            throw new ExtensionException("Missing required field: version");
        }
        if (!isset($data['main']) || !is_string($data['main'])) {
            throw new ExtensionException("Missing required field: main");
        }
        
        $dependencies = [];
        if (isset($data['dependencies']) && is_array($data['dependencies'])) {
            foreach ($data['dependencies'] as $dep) {
                if (is_string($dep)) {
                    $dependencies[] = $dep;
                }
            }
        }
        
        return new ExtensionMeta(
            $data['id'],
            $data['name'],
            $data['version'],
            $data['author'] ?? "",
            $data['description'] ?? "",
            $data['main'],
            $dependencies
        );
    }
}
