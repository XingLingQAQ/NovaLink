package com.nova.chat.client.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the external lang override directory feature of {@link I18n}.
 *
 * <p>Proves:
 * <ul>
 *   <li>A brand-new locale (not on the classpath) loads entirely from an
 *       external {@code lang/<locale>.properties} file.</li>
 *   <li>An external override of an EXISTING classpath key wins per-key
 *       (user customization beats the built-in value).</li>
 * </ul>
 *
 * <p>The external dir is reset in {@link AfterEach} so no state leaks into
 * other tests in the suite.
 */
@DisplayName("I18n external lang dir")
class I18nExternalDirTest {

    @TempDir
    Path tempDir;

    private Locale savedDefault;
    private File savedExternalDir;

    @BeforeEach
    void saveState() {
        savedDefault = I18n.getDefaultLocale();
        savedExternalDir = I18n.getExternalLangDir();
        // Start each test clean: no external override, fresh bundle cache.
        I18n.setExternalLangDir((File) null);
        I18n.invalidate();
    }

    @AfterEach
    void restoreState() {
        I18n.setExternalLangDir(savedExternalDir);
        I18n.setDefaultLocale(savedDefault);
        I18n.invalidate();
    }

    /**
     * Drops a brand-new locale (fr_FR, not on the classpath) into the external
     * lang dir and proves I18n loads it entirely from the external file —
     * i18n.tr(Locale.FRANCE, ...) returns the French value.
     */
    @Test
    @DisplayName("brand-new locale loads from external file only (fr_FR)")
    void newLocaleFromExternalFileOnly() throws IOException {
        File langDir = tempDir.resolve("lang").toFile();
        // Write lang/fr_FR.properties with one key.
        writeProperty(langDir, "fr_FR.properties", "chat.test", "Bonjour {0}");

        I18n.setExternalLangDir(tempDir.toFile());
        I18n.invalidate();

        // fr_FR is NOT a classpath bundle — this must come from the external file.
        String result = I18n.tr(Locale.FRANCE, "chat.test", "Monde");
        assertThat(result).isEqualTo("Bonjour Monde");
    }

    /**
     * Drops an override for an EXISTING classpath key (chat.join.joined in
     * zh_CN) into the external lang dir and proves the external value wins
     * per-key — user customization beats the built-in bundle.
     */
    @Test
    @DisplayName("external override wins per-key for an existing classpath key")
    void externalOverrideWinsPerKey() throws IOException {
        File langDir = tempDir.resolve("lang").toFile();
        // Override an existing zh_CN key with a distinctive external value.
        writeProperty(langDir, "zh_CN.properties", "chat.join.joined", "外部覆盖");

        I18n.setExternalLangDir(tempDir.toFile());
        I18n.invalidate();

        // No args -> returns the raw pattern; the external value must win.
        String result = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.join.joined");
        assertThat(result).isEqualTo("外部覆盖");

        // A key NOT in the external override file must still resolve from the
        // classpath bundle (proves merge, not replacement).
        String classpathKey = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.toggle.on");
        assertThat(classpathKey).isEqualTo("聊天已开启");
    }

    /**
     * Proves that with no external dir set, the classpath bundle is used
     * unchanged (the override feature is purely additive).
     */
    @Test
    @DisplayName("no external dir -> classpath bundle used unchanged")
    void noExternalDirUsesClasspath() {
        I18n.setExternalLangDir((File) null);
        I18n.invalidate();

        String result = I18n.tr(LocaleResolver.ROOT_LOCALE, "chat.join.joined", "global");
        assertThat(result).contains("已加入频道").contains("global");
    }

    /**
     * Proves a missing key in an external-only locale still falls back through
     * the zh_CN chain — external locales inherit the fallback, not the key echo.
     */
    @Test
    @DisplayName("missing key in external-only locale falls back to zh_CN")
    void missingKeyInExternalLocaleFallsBack() throws IOException {
        File langDir = tempDir.resolve("lang").toFile();
        // fr_FR external file with only one key; a different key should fall
        // back to the zh_CN classpath bundle.
        writeProperty(langDir, "fr_FR.properties", "chat.test", "Bonjour {0}");

        I18n.setExternalLangDir(tempDir.toFile());
        I18n.invalidate();

        // chat.toggle.on is absent from the fr_FR external file -> should fall
        // back to the zh_CN classpath value.
        String result = I18n.tr(Locale.FRANCE, "chat.toggle.on");
        assertThat(result).isEqualTo("聊天已开启");
    }

    // ============================ helpers ============================

    /**
     * Writes a single key/value pair to {@code <dir>/<file>} as UTF-8. Creates
     * the parent directory if missing.
     */
    private static void writeProperty(File dir, String file, String key, String value) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Path target = dir.toPath().resolve(file);
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            w.write(key + "=" + value + "\n");
        }
    }
}
