package com.nova.chat.common.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Installs and upgrades YAML configuration files from a bundled template.
 * Existing values and unknown keys always win; only missing nodes are copied
 * from the template. Comments are retained through the SnakeYAML node API.
 */
public final class YamlConfigUpdater {

    private YamlConfigUpdater() {
    }

    /**
     * Updates a configuration file from a UTF-8 template stream.
     *
     * @param configPath target configuration path
     * @param templateInput bundled template stream
     * @param dynamicMappings mapping paths whose existing children are entirely user-owned
     * @return the performed update
     * @throws IOException when the template or user configuration cannot be read, parsed, or written
     */
    public static UpdateResult update(Path configPath, InputStream templateInput,
                                      Set<String> dynamicMappings) throws IOException {
        Objects.requireNonNull(templateInput, "Template input cannot be null");
        return update(configPath,
                new String(templateInput.readAllBytes(), StandardCharsets.UTF_8),
                dynamicMappings);
    }

    /**
     * Updates a configuration file from UTF-8 template content.
     */
    public static UpdateResult update(Path configPath, String templateContent,
                                      Set<String> dynamicMappings) throws IOException {
        Objects.requireNonNull(configPath, "Config path cannot be null");
        Objects.requireNonNull(templateContent, "Template content cannot be null");
        Set<String> dynamicPaths = dynamicMappings == null ? Set.of() : Set.copyOf(dynamicMappings);

        MappingNode templateRoot = composeMapping(templateContent, "Bundled configuration template");
        if (!Files.exists(configPath)) {
            writeAtomically(configPath, templateContent, false);
            return new UpdateResult(true, false, null);
        }

        String userContent = Files.readString(configPath, StandardCharsets.UTF_8);
        MappingNode userRoot = composeMapping(userContent, "Existing configuration");
        boolean changed = mergeMappings(userRoot, templateRoot, "", dynamicPaths);
        if (!changed) {
            return new UpdateResult(false, false, null);
        }

        String upgradedContent = serialize(userRoot);
        Path backupPath = writeAtomically(configPath, upgradedContent, true);
        return new UpdateResult(false, true, backupPath);
    }

    private static MappingNode composeMapping(String content, String sourceName) throws IOException {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setProcessComments(true);
            options.setAllowDuplicateKeys(false);
            Node node = new Yaml(options).compose(new StringReader(content));
            if (!(node instanceof MappingNode)) {
                throw new IOException(sourceName + " root must be a YAML mapping");
            }
            return (MappingNode) node;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(sourceName + " is invalid YAML: " + e.getMessage(), e);
        }
    }

    private static boolean mergeMappings(MappingNode target, MappingNode template,
                                         String parentPath, Set<String> dynamicMappings) {
        boolean changed = false;
        for (NodeTuple templateTuple : template.getValue()) {
            if (!(templateTuple.getKeyNode() instanceof ScalarNode)) {
                continue;
            }

            String key = ((ScalarNode) templateTuple.getKeyNode()).getValue();
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;
            int targetIndex = findTupleIndex(target, key);
            if (targetIndex < 0) {
                target.getValue().add(templateTuple);
                changed = true;
                continue;
            }

            NodeTuple targetTuple = target.getValue().get(targetIndex);
            Node targetValue = targetTuple.getValueNode();
            Node templateValue = templateTuple.getValueNode();
            if (!targetValue.getNodeId().equals(templateValue.getNodeId())) {
                // An existing value belongs to the operator, even when its
                // type is wrong. The platform loader will reject it with a
                // field-specific error instead of silently replacing it.
                continue;
            }

            if (templateValue instanceof MappingNode) {
                if (!dynamicMappings.contains(path)) {
                    changed |= mergeMappings((MappingNode) targetValue,
                            (MappingNode) templateValue, path, dynamicMappings);
                }
                continue;
            }

            if (templateValue instanceof SequenceNode) {
                continue;
            }

        }
        return changed;
    }

    private static int findTupleIndex(MappingNode mapping, String key) {
        List<NodeTuple> tuples = mapping.getValue();
        for (int i = 0; i < tuples.size(); i++) {
            Node keyNode = tuples.get(i).getKeyNode();
            if (keyNode instanceof ScalarNode
                    && key.equals(((ScalarNode) keyNode).getValue())) {
                return i;
            }
        }
        return -1;
    }

    private static String serialize(Node node) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setProcessComments(true);
        options.setSplitLines(false);
        options.setWidth(4096);

        StringWriter writer = new StringWriter();
        new Yaml(options).serialize(node, writer);
        return writer.toString();
    }

    private static Path writeAtomically(Path configPath, String content,
                                        boolean createBackup) throws IOException {
        Path absolutePath = configPath.toAbsolutePath();
        Path parent = absolutePath.getParent();
        if (parent == null) {
            throw new IOException("Configuration path has no parent directory: " + configPath);
        }
        Files.createDirectories(parent);

        Path tempPath = Files.createTempFile(parent,
                "." + absolutePath.getFileName() + ".", ".tmp");
        Path backupPath = null;
        try {
            Files.writeString(tempPath, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (createBackup) {
                backupPath = absolutePath.resolveSibling(absolutePath.getFileName() + ".bak");
                Files.copy(absolutePath, backupPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            try {
                Files.move(tempPath, absolutePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempPath, absolutePath, StandardCopyOption.REPLACE_EXISTING);
            }
            return backupPath;
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    /** Result of a template installation or upgrade. */
    public record UpdateResult(boolean created, boolean updated, Path backupPath) {
        public boolean changed() {
            return created || updated;
        }
    }
}
