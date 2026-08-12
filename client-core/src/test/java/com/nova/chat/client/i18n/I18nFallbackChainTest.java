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
 * Explicit fallback-chain tests for {@link I18n}.
 *
 * <p>{@link I18nTest} touches the fallback briefly; this class makes each link
 * of the chain explicit:
 * <ol>
 *   <li>Requested-locale bundle missing a key that zh_CN has → zh_CN value.</li>
 *   <li>Key missing from BOTH requested and fallback → the key itself.</li>
 *   <li>A locale with NO bundle at all (and no external dir) → falls back to
 *       zh_CN, then to the key echo.</li>
 * </ol>
 *
 * <p>The two built-in classpath bundles ({@code messages_zh_CN.properties} and
 * {@code messages_en_US.properties}) are near-perfect mirrors, so to prove link
 * (1) — "missing from en_US but present in zh_CN" — we plant a brand-new key
 * into zh_CN via an external override file. That key is then present in zh_CN
 * only, so requesting it under en_US must fall back to the zh_CN value.
 */
@DisplayName("I18n fallback chain")
class I18nFallbackChainTest {

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
     * Key missing from en_US but present in zh_CN → returns the zh_CN value.
     *
     * <p>We plant {@code chat.test.fallback_only} into an external zh_CN
     * override file (a brand-new key absent from both classpath bundles).
     * The merged zh_CN bundle then has it; the en_US bundle does not. Requesting
     * it under en_US must fall back to zh_CN.
     */
    @Test
    @DisplayName("key missing from en_US but present in zh_CN returns zh_CN value")
    void missingFromEnUsPresentInZhCNFallsBack() throws IOException {
        File langDir = tempDir.resolve("lang").toFile();
        writeProperty(langDir, "zh_CN.properties",
                "chat.test.fallback_only", "来自中文兜底");

        I18n.setExternalLangDir(tempDir.toFile());
        I18n.invalidate();

        // en_US does NOT have this key → must fall back to zh_CN.
        String result = I18n.tr(LocaleResolver.EN_US, "chat.test.fallback_only");
        assertThat(result).isEqualTo("来自中文兜底");
    }

    /**
     * Key missing from BOTH the requested locale (en_US) and the fallback
     * (zh_CN) → the key string itself is returned.
     */
    @Test
    @DisplayName("key missing from both requested and fallback returns the key itself")
    void missingFromBothReturnsKey() {
        // No external dir → classpath bundles only.
        I18n.setExternalLangDir((File) null);
        I18n.invalidate();

        String missingKey = "this.key.does.not.exist.in.either.bundle";
        // en_US has no such key → falls to zh_CN → also absent → key echo.
        assertThat(I18n.tr(LocaleResolver.EN_US, missingKey)).isEqualTo(missingKey);
        // zh_CN direct also echoes.
        assertThat(I18n.tr(LocaleResolver.ROOT_LOCALE, missingKey)).isEqualTo(missingKey);
    }

    /**
     * A locale with NO bundle at all (Locale.FRANCE — no fr_FR classpath bundle,
     * no external dir) falls back to zh_CN; a key present in zh_CN resolves to
     * the Chinese value.
     */
    @Test
    @DisplayName("locale with no bundle at all falls back to zh_CN")
    void noBundleAtAllFallsBackToZhCN() {
        // No external dir, no fr_FR classpath bundle → fr_FR has no bundle.
        I18n.setExternalLangDir((File) null);
        I18n.invalidate();

        String result = I18n.tr(Locale.FRANCE, "chat.toggle.on");
        // Falls back to zh_CN → Chinese value.
        assertThat(result).isEqualTo("聊天已开启");
    }

    /**
     * A locale with no bundle at all, requesting a key missing from zh_CN too,
     * returns the key itself (the bottom of the chain).
     */
    @Test
    @DisplayName("no-bundle locale + missing key returns the key itself")
    void noBundleAndMissingKeyReturnsKey() {
        I18n.setExternalLangDir((File) null);
        I18n.invalidate();

        String missingKey = "absent.from.everywhere";
        assertThat(I18n.tr(Locale.FRANCE, missingKey)).isEqualTo(missingKey);
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
