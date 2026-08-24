package com.nova.chat.velocity.config;

import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-preserving TOML template updater for Velocity's config.toml.
 * toml4j validates both documents; the updater then inserts only missing
 * template assignments or tables, leaving existing text and comments intact.
 */
final class TomlConfigUpdater {
    private static final Pattern TABLE_PATTERN =
            Pattern.compile("^\\s*\\[([^\\[\\]]+)]\\s*(?:#.*)?$");
    private static final Pattern ASSIGNMENT_PATTERN =
            Pattern.compile("^\\s*((?:\"(?:\\\\.|[^\"])*\")|(?:'[^']*')|(?:[^=\\s]+))\\s*=");

    private TomlConfigUpdater() {
    }

    static UpdateResult update(Path configPath, InputStream templateInput,
                               Set<String> dynamicTables) throws IOException {
        Objects.requireNonNull(templateInput, "Template input cannot be null");
        String template = new String(templateInput.readAllBytes(), StandardCharsets.UTF_8);
        validate(template, "Bundled configuration template");

        if (!Files.exists(configPath)) {
            writeAtomically(configPath, template, false);
            return new UpdateResult(true, false, null);
        }

        String user = Files.readString(configPath, StandardCharsets.UTF_8);
        Toml userToml = validate(user, "Existing configuration");
        Document userDocument = Document.parse(user);

        // Older bundled templates placed debug under [format.channels]. Carry
        // that user value into the new root-level key during the first upgrade.
        if (!userDocument.rootKeys().contains("debug")) {
            Toml legacyChannels = userToml.getTable("format.channels");
            Boolean legacyDebug = legacyChannels != null
                    ? legacyChannels.getBoolean("debug") : null;
            if (legacyDebug != null) {
                template = replaceRootAssignment(template, "debug", legacyDebug.toString());
            }
        }

        String upgraded = merge(user, template,
                dynamicTables == null ? Set.of() : Set.copyOf(dynamicTables));
        if (upgraded.equals(user)) {
            return new UpdateResult(false, false, null);
        }
        validate(upgraded, "Upgraded configuration");
        Path backup = writeAtomically(configPath, upgraded, true);
        return new UpdateResult(false, true, backup);
    }

    private static Toml validate(String content, String source) throws IOException {
        try {
            return new Toml().read(content);
        } catch (Exception e) {
            throw new IOException(source + " is invalid TOML: " + e.getMessage(), e);
        }
    }

    private static String merge(String userContent, String templateContent,
                                Set<String> dynamicTables) {
        Document user = Document.parse(userContent);
        Document template = Document.parse(templateContent);
        Map<Integer, List<String>> insertions = new LinkedHashMap<>();
        List<String> append = new ArrayList<>();

        addMissingAssignments(user.root(), template.root(), insertions, dynamicTables);
        for (Section templateSection : template.sections()) {
            if (templateSection.name().isEmpty()) {
                continue;
            }
            Section userSection = user.section(templateSection.name());
            if (userSection == null) {
                appendSection(append, templateSection);
            } else if (!dynamicTables.contains(templateSection.name())) {
                addMissingAssignments(userSection, templateSection, insertions, dynamicTables);
            }
        }

        if (insertions.isEmpty() && append.isEmpty()) {
            return userContent;
        }

        List<String> result = new ArrayList<>(user.lines());
        List<Integer> indices = new ArrayList<>(insertions.keySet());
        indices.sort(java.util.Comparator.reverseOrder());
        for (Integer index : indices) {
            result.addAll(index, insertions.get(index));
        }
        if (!append.isEmpty()) {
            while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
                result.remove(result.size() - 1);
            }
            if (!result.isEmpty()) {
                result.add("");
            }
            result.addAll(append);
        }

        String merged = String.join(user.newline(), result);
        return user.endsWithNewline() ? merged + user.newline() : merged;
    }

    private static void addMissingAssignments(Section user, Section template,
                                              Map<Integer, List<String>> insertions,
                                              Set<String> dynamicTables) {
        if (dynamicTables.contains(template.name())) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Assignment assignment : template.assignments()) {
            if (!user.keys().contains(assignment.key())) {
                missing.addAll(assignment.sourceLines());
            }
        }
        if (!missing.isEmpty()) {
            List<String> lines = insertions.computeIfAbsent(user.endIndex(), ignored -> new ArrayList<>());
            if (!lines.isEmpty() || (user.endIndex() > user.startIndex())) {
                lines.add("");
            }
            lines.addAll(missing);
        }
    }

    private static void appendSection(List<String> target, Section section) {
        if (!target.isEmpty()) {
            target.add("");
        }
        target.addAll(section.sourceLines());
    }

    private static String replaceRootAssignment(String template, String key, String value) {
        Document document = Document.parse(template);
        List<String> lines = new ArrayList<>(document.lines());
        for (Assignment assignment : document.root().assignments()) {
            if (assignment.key().equals(key)) {
                int lineIndex = assignment.assignmentLineIndex();
                String oldLine = lines.get(lineIndex);
                int equals = oldLine.indexOf('=');
                String suffix = "";
                int comment = oldLine.indexOf('#', equals + 1);
                if (comment >= 0) {
                    suffix = " " + oldLine.substring(comment).stripLeading();
                }
                lines.set(lineIndex, oldLine.substring(0, equals + 1) + " " + value + suffix);
                break;
            }
        }
        String result = String.join(document.newline(), lines);
        return document.endsWithNewline() ? result + document.newline() : result;
    }

    private static Path writeAtomically(Path path, String content, boolean backup) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Configuration path has no parent directory: " + path);
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "." + absolute.getFileName() + ".", ".tmp");
        Path backupPath = null;
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (backup) {
                backupPath = absolute.resolveSibling(absolute.getFileName() + ".bak");
                Files.copy(absolute, backupPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            try {
                Files.move(temp, absolute,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            return backupPath;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    record UpdateResult(boolean created, boolean updated, Path backupPath) {
    }

    private record Assignment(String key, List<String> sourceLines, int assignmentLineIndex) {
    }

    private record Section(String name, int startIndex, int endIndex,
                           List<String> sourceLines, List<Assignment> assignments,
                           Set<String> keys) {
    }

    private record Document(List<String> lines, String newline, boolean endsWithNewline,
                            Section root, List<Section> sections,
                            Map<String, Section> sectionsByName) {
        static Document parse(String content) {
            String newline = content.contains("\r\n") ? "\r\n" : "\n";
            boolean endsWithNewline = content.endsWith("\n") || content.endsWith("\r");
            String[] raw = content.split("\\r?\\n", -1);
            List<String> lines = new ArrayList<>(List.of(raw));
            if (endsWithNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }

            List<Integer> headers = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = TABLE_PATTERN.matcher(lines.get(i));
                if (matcher.matches()) {
                    headers.add(i);
                    names.add(matcher.group(1).trim());
                }
            }

            int rootEnd = headers.isEmpty() ? lines.size() : headers.get(0);
            Section root = parseSection("", 0, rootEnd, lines, false);
            List<Section> sections = new ArrayList<>();
            Map<String, Section> byName = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                int start = headers.get(i);
                int end = i + 1 < headers.size() ? headers.get(i + 1) : lines.size();
                Section section = parseSection(names.get(i), start, end, lines, true);
                sections.add(section);
                byName.put(section.name(), section);
            }
            return new Document(List.copyOf(lines), newline, endsWithNewline,
                    root, List.copyOf(sections), Map.copyOf(byName));
        }

        private static Section parseSection(String name, int start, int end,
                                            List<String> lines, boolean hasHeader) {
            List<Assignment> assignments = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>();
            int contentStart = hasHeader ? start + 1 : start;
            int previousAssignment = contentStart - 1;
            for (int i = contentStart; i < end; i++) {
                Matcher matcher = ASSIGNMENT_PATTERN.matcher(lines.get(i));
                if (!matcher.find()) {
                    continue;
                }
                String key = unquote(matcher.group(1).trim());
                int groupStart = i;
                if (hasHeader || previousAssignment >= contentStart) {
                    for (int candidate = i - 1; candidate > previousAssignment; candidate--) {
                        String trimmed = lines.get(candidate).trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            groupStart = candidate;
                        } else {
                            break;
                        }
                    }
                }
                assignments.add(new Assignment(key,
                        List.copyOf(lines.subList(groupStart, i + 1)), i));
                keys.add(key);
                previousAssignment = i;
            }
            int sourceStart = start;
            if (hasHeader) {
                for (int candidate = start - 1; candidate >= 0; candidate--) {
                    String trimmed = lines.get(candidate).trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        sourceStart = candidate;
                    } else {
                        break;
                    }
                }
            }
            return new Section(name, start, end,
                    List.copyOf(lines.subList(sourceStart, end)),
                    List.copyOf(assignments), Set.copyOf(keys));
        }

        private static String unquote(String key) {
            if (key.length() >= 2
                    && ((key.startsWith("\"") && key.endsWith("\""))
                    || (key.startsWith("'") && key.endsWith("'")))) {
                return key.substring(1, key.length() - 1);
            }
            return key;
        }

        Section section(String name) {
            return sectionsByName.get(name);
        }

        Set<String> rootKeys() {
            return root.keys();
        }
    }
}
