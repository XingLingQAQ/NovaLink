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
 * Hot-add of a brand-new locale AFTER the {@link I18n} instance is already
 * serving requests.
 *
 * <p>{@link I18nExternalDirTest} sets the external dir BEFORE the first lookup,
 * so the bundle is loaded fresh with the external file already present. This
 * class covers the genuinely different "hot add" path:
 * <ol>
 *   <li>The external dir is set and a first lookup for {@code fr_FR} already
 *       happened — caching the {@code fr_FR} bundle as the zh_CN fallback
 *       (because no fr_FR file exists yet).</li>
 *   <li>A {@code lang/fr_FR.properties} file is then dropped into the external
 *       dir mid-test.</li>
 *   <li>Calling {@link I18n#invalidate()} clears the bundle cache so the next
 *       lookup reloads from the (now-populated) external dir.</li>
 *   <li>The {@code fr_FR} locale now resolves from the external file.</li>
 * </ol>
 *
 * <p>{@link I18n#invalidate()} already exists as the cache-clear mechanism, so
 * no new {@code clearCache()} / {@code reload()} method is needed on
 * {@link I18n} — the "hot add" recipe is "write the file, then
 * {@code invalidate()}".
 */
@DisplayName("I18n hot-add new locale after construction")
class I18nNewLocaleHotAddTest {

    @TempDir
    Path tempDir;

    private Locale savedDefault;
    private File savedExternalDir;

    @BeforeEach
    void saveState() {
        savedDefault = I18n.getDefaultLocale();
        savedExternalDir = I18n.getExternalLangDir();
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
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
     * The core hot-add scenario: external dir is set and fr_FR was already
     * looked up (caching the zh_CN fallback), then a fr_FR file appears, then
     * invalidate() + re-lookup resolves French.
     */
    @Test
    @DisplayName("locale unresolvable before hot-add, resolvable after file drop + invalidate()")
    void hotAddNewLocaleAfterFirstLookup() throws IOException {
        // 1. Set the external dir (empty — no fr_FR file yet) and prime the
        //    bundle cache with a first lookup for fr_FR.
        I18n.setExternalLangDir(tempDir.toFile());
        I18n.invalidate();

        String before = I18n.tr(Locale.FRANCE, "chat.toggle.on");
        // No fr_FR bundle yet → falls back to zh_CN.
        assertThat(before).isEqualTo("聊天已开启");

        // 2. Drop a fr_FR.properties into the external lang dir mid-test.
        File langDir = tempDir.resolve("lang").toFile();
        writeProperty(langDir, "fr_FR.properties", "chat.toggle.on", "Chat activé");

        // 3. Without invalidate(), the cached fr_FR→zh_CN bundle is still in
        //    place, so the new file would NOT be visible yet.
        String withoutInvalidate = I18n.tr(Locale.FRANCE, "chat.toggle.on");
        assertThat(withoutInvalidate)
                .as("cached bundle still points at zh_CN before invalidate()")
                .isEqualTo("聊天已开启");

        // 4. invalidate() clears the cache → next lookup reloads from disk.
        I18n.invalidate();
        String after = I18n.tr(Locale.FRANCE, "chat.toggle.on");
        assertThat(after).isEqualTo("Chat activé");
    }

    /**
     * A hot-added locale with interpolation: the external fr_FR file uses a
     * {0} placeholder, and after invalidate() the new locale interpolates
     * correctly from the external file.
     */
    @Test
    @DisplayName("hot-added locale supports {0} interpolation from external file")
    void hotAddLocaleWithInterpolation() throws IOException {
        I18n.setExternalLangDir(tempDir.toFile());
        I18n.invalidate();

        // Prime the cache: fr_FR is absent and chat.test is absent from zh_CN
        // too, so the lookup echoes the key itself (no crash, no French yet).
        assertThat(I18n.tr(Locale.FRANCE, "chat.test", "Monde"))
                .isEqualTo("chat.test");

        // Drop a fr_FR file with a placeholder key.
        File langDir = tempDir.resolve("lang").toFile();
        writeProperty(langDir, "fr_FR.properties", "chat.test", "Bonjour {0}");

        I18n.invalidate();
        String result = I18n.tr(Locale.FRANCE, "chat.test", "Monde");
        assertThat(result).isEqualTo("Bonjour Monde");
    }

    // ============================ helpers ============================

    private static void writeProperty(File dir, String file, String key, String value)
            throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Path target = dir.toPath().resolve(file);
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            w.write(key + "=" + value + "\n");
        }
    }
}
