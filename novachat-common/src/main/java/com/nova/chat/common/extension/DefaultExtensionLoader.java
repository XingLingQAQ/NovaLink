package com.nova.chat.common.extension;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link ExtensionLoader}.
 * 
 * <p>This loader scans the extensions directory for JAR files,
 * parses extension.yml metadata, and creates extension instances.
 * 
 * <p>Extensions that fail to load are logged but do not prevent
 * other extensions from loading (isolation property).
 */
public class DefaultExtensionLoader implements ExtensionLoader {
    
    private static final String EXTENSION_YML = "extension.yml";
    private static final Logger LOGGER = Logger.getLogger(DefaultExtensionLoader.class.getName());
    
    private final ExtensionMetaParser metaParser;
    private final Map<String, NovaChatExtension> loadedExtensions;
    private final Map<String, URLClassLoader> classLoaders;
    
    public DefaultExtensionLoader() {
        this.metaParser = new ExtensionMetaParser();
        this.loadedExtensions = new ConcurrentHashMap<>();
        this.classLoaders = new ConcurrentHashMap<>();
    }
    
    @Override
    public List<NovaChatExtension> loadExtensions(Path extensionsDir) {
        List<NovaChatExtension> extensions = new ArrayList<>();
        
        if (!Files.exists(extensionsDir)) {
            try {
                Files.createDirectories(extensionsDir);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to create extensions directory", e);
            }
            return extensions;
        }
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(extensionsDir, "*.jar")) {
            for (Path jarPath : stream) {
                try {
                    NovaChatExtension extension = loadExtension(jarPath);
                    if (extension != null) {
                        extensions.add(extension);
                        loadedExtensions.put(extension.getMeta().getId(), extension);
                        LOGGER.info("Loaded extension: " + extension.getMeta().getName() + 
                                   " v" + extension.getMeta().getVersion());
                    }
                } catch (ExtensionException e) {
                    // Log error but continue loading other extensions (isolation)
                    LOGGER.log(Level.WARNING, "Failed to load extension from " + jarPath.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to scan extensions directory", e);
        }
        
        return extensions;
    }

    
    /**
     * Loads a single extension from a JAR file.
     * 
     * @param jarPath path to the JAR file
     * @return the loaded extension, or null if loading fails
     * @throws ExtensionException if the extension cannot be loaded
     */
    private NovaChatExtension loadExtension(Path jarPath) throws ExtensionException {
        ExtensionMeta meta = loadMeta(jarPath);
        
        try {
            URL jarUrl = jarPath.toUri().toURL();
            URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarUrl},
                getClass().getClassLoader()
            );
            
            Class<?> mainClass = classLoader.loadClass(meta.getMain());
            
            if (!NovaChatExtension.class.isAssignableFrom(mainClass)) {
                classLoader.close();
                throw new ExtensionException(meta.getId(), 
                    "Main class " + meta.getMain() + " does not implement NovaChatExtension");
            }
            
            @SuppressWarnings("unchecked")
            Class<? extends NovaChatExtension> extensionClass = 
                (Class<? extends NovaChatExtension>) mainClass;
            
            NovaChatExtension extension = createExtensionInstance(extensionClass, meta);
            classLoaders.put(meta.getId(), classLoader);
            
            return extension;
        } catch (ClassNotFoundException e) {
            throw new ExtensionException(meta.getId(), 
                "Main class not found: " + meta.getMain(), e);
        } catch (IOException e) {
            throw new ExtensionException(meta.getId(), 
                "Failed to load JAR: " + jarPath.getFileName(), e);
        }
    }
    
    /**
     * Loads extension metadata from a JAR file.
     * 
     * @param jarPath path to the JAR file
     * @return the parsed extension metadata
     * @throws ExtensionException if metadata cannot be loaded or parsed
     */
    private ExtensionMeta loadMeta(Path jarPath) throws ExtensionException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(EXTENSION_YML);
            if (entry == null) {
                throw new ExtensionException("Missing " + EXTENSION_YML + " in " + jarPath.getFileName());
            }
            
            try (InputStream is = jarFile.getInputStream(entry)) {
                return metaParser.parse(is);
            }
        } catch (IOException e) {
            throw new ExtensionException("Failed to read " + jarPath.getFileName(), e);
        }
    }
    
    /**
     * Creates an instance of the extension class.
     * 
     * @param extensionClass the extension class
     * @param meta the extension metadata
     * @return the extension instance
     * @throws ExtensionException if instantiation fails
     */
    private NovaChatExtension createExtensionInstance(
            Class<? extends NovaChatExtension> extensionClass, 
            ExtensionMeta meta) throws ExtensionException {
        try {
            // Try constructor with ExtensionMeta parameter first
            try {
                return extensionClass.getConstructor(ExtensionMeta.class).newInstance(meta);
            } catch (NoSuchMethodException e) {
                // Fall back to no-arg constructor
                NovaChatExtension extension = extensionClass.getConstructor().newInstance();
                // If extension doesn't have meta set, wrap it
                if (extension.getMeta() == null) {
                    return new ExtensionWrapper(extension, meta);
                }
                return extension;
            }
        } catch (Exception e) {
            throw new ExtensionException(meta.getId(), 
                "Failed to instantiate extension: " + meta.getMain(), e);
        }
    }
    
    @Override
    public void enableExtension(NovaChatExtension extension) throws ExtensionException {
        try {
            extension.onEnable();
            LOGGER.info("Enabled extension: " + extension.getMeta().getName());
        } catch (Exception e) {
            throw new ExtensionException(extension.getMeta().getId(), 
                "Failed to enable extension", e);
        }
    }
    
    @Override
    public void disableExtension(NovaChatExtension extension) {
        try {
            extension.onDisable();
            LOGGER.info("Disabled extension: " + extension.getMeta().getName());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, 
                "Error disabling extension: " + extension.getMeta().getName(), e);
        }
        
        // Close the class loader
        String id = extension.getMeta().getId();
        URLClassLoader classLoader = classLoaders.remove(id);
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to close class loader for " + id, e);
            }
        }
        
        loadedExtensions.remove(id);
    }
    
    @Override
    public List<NovaChatExtension> getLoadedExtensions() {
        return Collections.unmodifiableList(new ArrayList<>(loadedExtensions.values()));
    }
    
    @Override
    public NovaChatExtension getExtension(String id) {
        return loadedExtensions.get(id);
    }
    
    /**
     * Wrapper class for extensions that don't provide their own metadata.
     */
    private static class ExtensionWrapper implements NovaChatExtension {
        private final NovaChatExtension delegate;
        private final ExtensionMeta meta;
        
        ExtensionWrapper(NovaChatExtension delegate, ExtensionMeta meta) {
            this.delegate = delegate;
            this.meta = meta;
        }
        
        @Override
        public void onEnable() {
            delegate.onEnable();
        }
        
        @Override
        public void onDisable() {
            delegate.onDisable();
        }
        
        @Override
        public ExtensionMeta getMeta() {
            return meta;
        }
    }
}
