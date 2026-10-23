package com.nova.chat.client.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the constraint that broke the Sponge plugin on the real-server E2E.
 *
 * <p>Sponge loads every plugin as a NAMED module on the module layer (the
 * plugin jar gets an automatic module name — {@code novachat} for this one).
 * Inside a named module {@code ResourceBundle.getBundle(baseName, locale,
 * Control)} throws before it ever reaches {@code Control.newBundle()}:
 *
 * <pre>
 *   UnsupportedOperationException: ResourceBundle.Control not supported in
 *   named modules
 *     at java.util.ResourceBundle.checkNamedModule
 *     at com.nova.chat.client.i18n.I18n.loadBundle(I18n.java:339)
 *     at com.nova.chat.client.sponge.command.NovaChatCommand.buildHelpCommand
 *     at com.nova.chat.client.sponge.NovaChatSponge.onRegisterCommands
 * </pre>
 *
 * <p>{@code buildHelpCommand()} looks up its description through {@code I18n.tr},
 * so that throw unwound out of {@code buildCommand()} and the entire {@code /nc}
 * tree was never registered — every subcommand answered "Unknown or incomplete
 * command" and the E2E saw zero plugin-produced lines.
 *
 * <p>These tests cannot create a named module around this class (surefire runs
 * it on the classpath), so they assert the two things that make the bug
 * impossible instead:
 * <ol>
 *   <li>the source contains no {@code ResourceBundle.Control} subclass or
 *       {@code getBundle(..., Control)} call;</li>
 *   <li>the hand-rolled loader still resolves the bundled bundle and decodes
 *       it as UTF-8 (the behaviour the old Control provided).</li>
 * </ol>
 */
@DisplayName("I18n named-module safety")
class I18nNamedModuleTest {

    /**
     * The defence itself: no Control-based lookup anywhere in {@link I18n}.
     *
     * <p>Reading the source is deliberate. A behavioural test would pass on the
     * classpath even if the Control call came back, because the classpath is an
     * unnamed module where {@code checkNamedModule()} does not fire. The
     * failure only reproduces under Sponge, so the source is the only place the
     * regression is visible to a plain unit test.
     */
    @Test
    @DisplayName("I18n source never calls ResourceBundle.getBundle(..., Control)")
    void sourceAvoidsResourceBundleControl() throws Exception {
        // Strip comments first: the class documents the bug in prose (and names
        // the forbidden APIs while doing so), so scanning raw text would flag
        // the explanation instead of the code.
        String code = stripComments(readSource("com/nova/chat/client/i18n/I18n.java"));

        assertThat(code)
                .as("a named module rejects Control-based bundles outright; "
                        + "see loadBundle()'s comment for the Sponge stack trace")
                .doesNotContain("ResourceBundle.Control")
                .doesNotContain("extends ResourceBundle.Control");

        // Any getBundle overload taking a Control argument — third positional,
        // or passed by name — reintroduces the named-module throw.
        assertThat(code)
                .as("any Control-taking getBundle call reintroduces the throw")
                .doesNotContain("ResourceBundle.getBundle(BUNDLE_RESOURCE_PREFIX, locale, ");
    }

    /**
     * Removes {@code //} line comments and {@code /* ... *&#47;} block comments.
     * String literals are not special-cased; nothing in {@link I18n} contains
     * comment markers inside a literal.
     */
    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    /** The replacement loader still finds and UTF-8-decodes the bundled files. */
    @Test
    @DisplayName("bundled bundles load by hand as UTF-8")
    void bundledBundlesResolveAsUtf8() {
        assertThat(I18n.tr(Locale.US, "chat.command.help.title"))
                .as("en_US bundle must resolve without a ResourceBundle control")
                .contains("NovaChat Help");

        // zh_CN carries non-ASCII text; decoding it as ISO-8859-1 (the
        // ResourceBundle default without a Control) would mangle it.
        assertThat(I18n.tr(Locale.SIMPLIFIED_CHINESE, "chat.command.help.title"))
                .as("zh_CN bundle must decode as UTF-8, not ISO-8859-1")
                .contains("NovaChat");
    }

    private static String readSource(String resourcePath) throws Exception {
        // Gradle runs tests with the module directory as the working dir, but
        // fall back to the repo-root-relative path so the test also works when
        // invoked from the root (as some IDE run configurations do).
        Path path = Paths.get("src/main/java", resourcePath);
        if (!Files.isRegularFile(path)) {
            path = Paths.get("NovaChat/client-core/src/main/java", resourcePath);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
