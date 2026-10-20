package com.nova.link.bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-run seeding of {@code novalink.yml} for the embedded (plugin) case.
 *
 * <p>The core's bundled template binds the TCP and WebSocket/REST listeners to
 * {@code 0.0.0.0}. That is the right default for a standalone backend behind a
 * firewall, but a plugin that silently opens two ports on every interface of a
 * public Minecraft server is a security bug. This class copies the core's own
 * template (never a duplicated copy kept in this module, so it cannot drift)
 * and rewrites exactly one value: {@code server.bind-address}.
 *
 * <p>It also sets {@code server.insecure-allow-plaintext: true}, for the same
 * two reasons. First, the core's AUTH-002 gate fails closed, so a fresh install
 * would otherwise refuse to start at all -- measured in a real Purpur server:
 * "NovaLink backend failed to start: ... would start in plaintext". Second, the
 * gate is about NETWORK exposure; on a loopback-only listener there is no
 * passive observer on the wire, so the risk it guards against is not present.
 * The plaintext acknowledgement is therefore scoped to the loopback seed. When
 * seeding with {@code 0.0.0.0} (operator opted in), the template is written
 * verbatim and the gate still fails closed until they configure TLS.
 *
 * <p>Seeding happens ONLY when {@code novalink.yml} does not exist. An existing
 * file is never rewritten -- an operator who deliberately set {@code 0.0.0.0}
 * keeps it, and {@link NovaLinkBukkitPlugin} logs a warning with the actual
 * bound endpoints instead.
 */
final class BackendDefaults {

    static final String CONFIG_FILE = "novalink.yml";

    /** Where the core reads its template from; must stay in sync with ConfigLoader. */
    private static final String TEMPLATE_RESOURCE = "/novalink.yml";

    static final String LOOPBACK = "127.0.0.1";

    /**
     * Matches the bind-address entry including its indentation, so the
     * replacement keeps the YAML layout. The template has exactly one
     * {@code bind-address} key (under {@code server:}).
     */
    // [ \t] rather than \s on both ends: \s matches \r, so on a CRLF template
    // (the core's resource is CRLF) a greedy \s* would swallow the carriage
    // return and the rewrite would leave mixed line endings in an otherwise
    // CRLF file.
    private static final Pattern BIND_ADDRESS_LINE =
            Pattern.compile("^([ \\t]*)bind-address:[ \\t]*\\S+[ \\t]*$", Pattern.MULTILINE);

    private static final Pattern INSECURE_ALLOW_LINE =
            Pattern.compile("^([ \\t]*)insecure-allow-plaintext:[ \\t]*\\S+[ \\t]*$",
                    Pattern.MULTILINE);

    enum SeedResult {
        /** The file already existed; nothing was touched. */
        ALREADY_PRESENT,
        /** A template was written with the loopback default. */
        SEEDED_LOOPBACK,
        /** A template was written but the rewrite did not apply; the value is the template's. */
        SEEDED_UNMODIFIED
    }

    private BackendDefaults() {
    }

    /**
     * Writes {@code <dataDir>/novalink.yml} from the core template when it is
     * absent.
     *
     * @param dataDir      the plugin data folder
     * @param loopbackBind when {@code false}, the template is written verbatim
     * @return what happened, for the caller to log
     * @throws IOException when the template cannot be read or the file cannot be written
     */
    static SeedResult seedIfAbsent(Path dataDir, boolean loopbackBind) throws IOException {
        Path configFile = dataDir.resolve(CONFIG_FILE);
        if (Files.exists(configFile)) {
            return SeedResult.ALREADY_PRESENT;
        }

        String template = readTemplate();
        if (!loopbackBind) {
            writeAtomically(configFile, template);
            return SeedResult.SEEDED_UNMODIFIED;
        }

        Matcher matcher = BIND_ADDRESS_LINE.matcher(template);
        if (!matcher.find()) {
            // The template changed shape. Do not guess: write it verbatim and
            // let the post-start listener audit report the real bind address.
            writeAtomically(configFile, template);
            return SeedResult.SEEDED_UNMODIFIED;
        }
        String seeded = replaceValue(template, matcher, "bind-address: " + LOOPBACK);

        // A loopback-only listener has no passive observer on the wire, and the
        // core refuses to start without either TLS or this acknowledgement.
        Matcher insecure = INSECURE_ALLOW_LINE.matcher(seeded);
        if (insecure.find()) {
            seeded = replaceValue(seeded, insecure, "insecure-allow-plaintext: true");
        } else {
            // Template shape changed: leave the gate closed rather than guess.
            writeAtomically(configFile, seeded);
            return SeedResult.SEEDED_UNMODIFIED;
        }

        writeAtomically(configFile, seeded);
        return SeedResult.SEEDED_LOOPBACK;
    }

    /** Rewrites one matched YAML line, keeping its indentation. */
    private static String replaceValue(String yaml, Matcher match, String newLine) {
        return yaml.substring(0, match.start())
                + match.group(1) + newLine
                + yaml.substring(match.end());
    }

    private static String readTemplate() throws IOException {
        try (InputStream in = BackendDefaults.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled backend template is missing: " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes through a temp file in the same directory so a crash mid-write
     * cannot leave a truncated {@code novalink.yml} that would fail to parse on
     * the next boot.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** @return {@code true} when the address only listens on this machine. */
    static boolean isLoopback(String bindAddress) {
        if (bindAddress == null) {
            return false;
        }
        String a = bindAddress.trim();
        return "127.0.0.1".equals(a)
                || "::1".equals(a)
                || "[::1]".equals(a)
                || "localhost".equalsIgnoreCase(a);
    }
}
