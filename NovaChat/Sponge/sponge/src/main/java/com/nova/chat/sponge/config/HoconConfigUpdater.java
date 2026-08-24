package com.nova.chat.sponge.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Comment-preserving HOCON template installer and updater. */
public final class HoconConfigUpdater {
    private HoconConfigUpdater() {
    }

    public static UpdateResult update(Path configPath, InputStream templateInput,
                                      Set<String> dynamicMappings) throws IOException {
        Objects.requireNonNull(templateInput, "Template input cannot be null");
        String templateContent = new String(templateInput.readAllBytes(), StandardCharsets.UTF_8);
        CommentedConfigurationNode templateRoot = loadString(templateContent, "Bundled template");

        if (!Files.exists(configPath)) {
            writeTextAtomically(configPath, templateContent, false);
            return new UpdateResult(true, false, null);
        }

        CommentedConfigurationNode userRoot = loadPath(configPath, "Existing configuration");
        boolean changed = merge(userRoot, templateRoot, "",
                dynamicMappings == null ? Set.of() : Set.copyOf(dynamicMappings));
        if (!changed) {
            return new UpdateResult(false, false, null);
        }

        Path backup = saveNodeAtomically(configPath, userRoot, true);
        return new UpdateResult(false, true, backup);
    }

    /**
     * Converts the historical Sponge YAML file to HOCON when novachat.conf is
     * absent. The original YAML file is intentionally retained.
     */
    public static boolean migrateLegacyYaml(Path legacyYaml, Path configPath,
                                            InputStream templateInput,
                                            Set<String> dynamicMappings) throws IOException {
        if (Files.exists(configPath) || !Files.isRegularFile(legacyYaml)) {
            return false;
        }
        Objects.requireNonNull(templateInput, "Template input cannot be null");
        String templateContent = new String(templateInput.readAllBytes(), StandardCharsets.UTF_8);
        CommentedConfigurationNode root = loadString(templateContent, "Bundled template");

        CommentedConfigurationNode legacyRoot;
        try {
            legacyRoot = YamlConfigurationLoader.builder().path(legacyYaml).build().load();
        } catch (Exception e) {
            throw new IOException("Legacy config.yml is invalid YAML: " + e.getMessage(), e);
        }
        if (!legacyRoot.isMap()) {
            throw new IOException("Legacy config.yml root must be a YAML mapping");
        }
        overlayNode(root, legacyRoot, "",
                dynamicMappings == null ? Set.of() : Set.copyOf(dynamicMappings));
        saveNodeAtomically(configPath, root, false);
        return true;
    }

    private static CommentedConfigurationNode loadString(String content,
                                                          String sourceName) throws IOException {
        try {
            return HoconConfigurationLoader.builder()
                    .source(() -> new BufferedReader(new StringReader(content)))
                    .build()
                    .load();
        } catch (Exception e) {
            throw new IOException(sourceName + " is invalid HOCON: " + e.getMessage(), e);
        }
    }

    private static CommentedConfigurationNode loadPath(Path path,
                                                        String sourceName) throws IOException {
        try {
            return HoconConfigurationLoader.builder().path(path).build().load();
        } catch (Exception e) {
            throw new IOException(sourceName + " is invalid HOCON: " + e.getMessage(), e);
        }
    }

    private static boolean merge(CommentedConfigurationNode target,
                                 CommentedConfigurationNode template,
                                 String parentPath, Set<String> dynamicMappings) {
        boolean changed = false;
        for (Map.Entry<Object, CommentedConfigurationNode> entry
                : template.childrenMap().entrySet()) {
            Object key = entry.getKey();
            CommentedConfigurationNode sourceChild = entry.getValue();
            CommentedConfigurationNode targetChild = target.node(key);
            String path = parentPath.isEmpty()
                    ? String.valueOf(key) : parentPath + "." + key;

            if (targetChild.virtual()) {
                copyNode(targetChild, sourceChild);
                changed = true;
                continue;
            }
            if (sourceChild.isMap()) {
                if (targetChild.isMap() && !dynamicMappings.contains(path)) {
                    changed |= merge(targetChild, sourceChild, path, dynamicMappings);
                }
            }
        }
        return changed;
    }

    private static void copyNode(CommentedConfigurationNode target,
                                 CommentedConfigurationNode source) {
        target.from(source);
        copyComments(target, source);
    }

    private static void copyComments(CommentedConfigurationNode target,
                                     CommentedConfigurationNode source) {
        if (source.comment() != null) {
            target.comment(source.comment());
        }
        for (Map.Entry<Object, CommentedConfigurationNode> entry
                : source.childrenMap().entrySet()) {
            copyComments(target.node(entry.getKey()), entry.getValue());
        }
        for (int i = 0; i < source.childrenList().size(); i++) {
            copyComments(target.node(i), source.childrenList().get(i));
        }
    }

    private static void overlayNode(CommentedConfigurationNode target,
                                    CommentedConfigurationNode source,
                                    String parentPath,
                                    Set<String> dynamicMappings) {
        if (source.isMap()) {
            for (Map.Entry<Object, CommentedConfigurationNode> entry
                    : source.childrenMap().entrySet()) {
                Object key = entry.getKey();
                String path = parentPath.isEmpty()
                        ? String.valueOf(key) : parentPath + "." + key;
                CommentedConfigurationNode targetChild = target.node(key);
                if (dynamicMappings.contains(path)) {
                    copyNode(targetChild, entry.getValue());
                } else {
                    overlayNode(targetChild, entry.getValue(), path, dynamicMappings);
                }
            }
        } else {
            copyNode(target, source);
        }
    }

    private static Path saveNodeAtomically(Path configPath,
                                           CommentedConfigurationNode node,
                                           boolean backup) throws IOException {
        Path absolute = prepareParent(configPath);
        Path parent = absolute.getParent();
        Path temp = Files.createTempFile(parent, "." + absolute.getFileName() + ".", ".tmp");
        Path backupPath = null;
        try {
            HoconConfigurationLoader.builder().path(temp).build().save(node);
            // Parse the generated document before replacing the live file.
            loadPath(temp, "Generated configuration");
            if (backup) {
                backupPath = absolute.resolveSibling(absolute.getFileName() + ".bak");
                Files.copy(absolute, backupPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            move(temp, absolute);
            return backupPath;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path writeTextAtomically(Path configPath, String content,
                                            boolean backup) throws IOException {
        Path absolute = prepareParent(configPath);
        Path parent = absolute.getParent();
        Path temp = Files.createTempFile(parent, "." + absolute.getFileName() + ".", ".tmp");
        Path backupPath = null;
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            if (backup) {
                backupPath = absolute.resolveSibling(absolute.getFileName() + ".bak");
                Files.copy(absolute, backupPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            move(temp, absolute);
            return backupPath;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path prepareParent(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (absolute.getParent() == null) {
            throw new IOException("Configuration path has no parent directory: " + path);
        }
        Files.createDirectories(absolute.getParent());
        return absolute;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record UpdateResult(boolean created, boolean updated, Path backupPath) {
    }
}
