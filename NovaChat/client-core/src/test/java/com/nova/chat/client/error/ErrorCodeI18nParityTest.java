package com.nova.chat.client.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIFY-007 (Java slice): per-locale i18n parity / diff test for every
 * {@link ErrorCode} enum value.
 *
 * <p>Audit text (VERIFY-007): "NC-401、NC-403、NC-404、NC-411、NC-420 等是否每个
 * 平台均能展示本地化明确文案 | 生成错误码清单,与各语言 formatter 做自动差集检查".
 *
 * <p>The canonical code set is {@link ErrorCode#values()} (26 codes:
 * NC-400/401/403/404/409/410/411/420/429/430/431/432/433/434/435/436/437/
 * 438/439/500/501/502/503/504/510/511). For each code this test asserts that
 * BOTH {@code error.<code>.message} AND {@code error.<code>.suggestion} keys
 * exist with non-blank values in EACH of:
 * <ul>
 *   <li>{@code lang/messages_zh_CN.properties}</li>
 *   <li>{@code lang/messages_en_US.properties}</li>
 * </ul>
 *
 * <p><b>Why a dedicated diff test (not the existing {@code ErrorCodeTest}).</b>
 * The existing {@code ErrorCodeTest.messageIsNonBlank} / {@code suggestionIsNonBlank}
 * go through {@code I18n.tr(...)} which falls back from en_US → zh_CN → raw key
 * echo. A key missing <em>only</em> in en_US therefore passes the existing test
 * (silent fallback to zh_CN). This diff test loads each properties file
 * <b>directly</b> via the classpath (mirroring {@code Utf8Control}) so a
 * per-locale gap is detected independently — no fallback masking.
 *
 * <p>Purely additive: new file, new class name. No production source modified.
 */
@DisplayName("VERIFY-007: ErrorCode i18n parity (zh_CN + en_US, direct bundle load)")
class ErrorCodeI18nParityTest {

    private static final String ZH_CN_RESOURCE = "lang/messages_zh_CN.properties";
    private static final String EN_US_RESOURCE = "lang/messages_en_US.properties";

    /**
     * Loads a properties bundle directly from the classpath as UTF-8, mirroring
     * {@code Utf8Control.newBundle}. Returns null if the resource is missing so
     * the caller can report a precise failure.
     */
    private static ResourceBundle loadBundleDirectly(String classpathResource) throws IOException {
        ClassLoader loader = ErrorCodeI18nParityTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(classpathResource)) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new PropertyResourceBundle(reader);
            }
        }
    }

    /**
     * Collects the missing/blank keys for a single locale bundle. Returns an
     * empty list if everything is present and non-blank.
     */
    private static List<String> findMissingKeys(ResourceBundle bundle, String localeTag) {
        List<String> missing = new ArrayList<>();
        if (bundle == null) {
            missing.add("[" + localeTag + "] bundle missing on classpath: cannot load " + ZH_CN_RESOURCE);
            return missing;
        }
        for (ErrorCode code : ErrorCode.values()) {
            String codeStr = code.getCode();  // e.g. "NC-401"
            String msgKey = "error." + codeStr + ".message";
            String sugKey = "error." + codeStr + ".suggestion";

            String msgVal = bundle.getString(msgKey);
            if (msgVal == null || msgVal.isBlank()) {
                missing.add("[" + localeTag + "] " + msgKey + " is missing or blank");
            }
            String sugVal = bundle.getString(sugKey);
            if (sugVal == null || sugVal.isBlank()) {
                missing.add("[" + localeTag + "] " + sugKey + " is missing or blank");
            }
        }
        return missing;
    }

    @Test
    @DisplayName("zh_CN: every ErrorCode has non-blank error.<code>.message and .suggestion")
    void zhCn_hasAllErrorKeys() throws IOException {
        ResourceBundle bundle = loadBundleDirectly(ZH_CN_RESOURCE);
        List<String> missing = findMissingKeys(bundle, "zh_CN");
        assertThat(missing)
                .as("zh_CN missing/blank i18n keys for ErrorCode (expected zero)")
                .isEmpty();
    }

    @Test
    @DisplayName("en_US: every ErrorCode has non-blank error.<code>.message and .suggestion")
    void enUs_hasAllErrorKeys() throws IOException {
        ResourceBundle bundle = loadBundleDirectly(EN_US_RESOURCE);
        List<String> missing = findMissingKeys(bundle, "en_US");
        assertThat(missing)
                .as("en_US missing/blank i18n keys for ErrorCode (expected zero)")
                .isEmpty();
    }

    @Test
    @DisplayName("both locales cover all 26 ErrorCode values (canonical code-set diff)")
    void bothLocales_coverAllErrorCodes() throws IOException {
        ResourceBundle zh = loadBundleDirectly(ZH_CN_RESOURCE);
        ResourceBundle en = loadBundleDirectly(EN_US_RESOURCE);

        List<String> allGaps = new ArrayList<>();
        allGaps.addAll(findMissingKeys(zh, "zh_CN"));
        allGaps.addAll(findMissingKeys(en, "en_US"));

        assertThat(allGaps)
                .as("per-locale i18n parity gaps (expected zero): " + allGaps)
                .isEmpty();

        // Sanity: the enum has the expected 26 codes. This guards against a
        // future code being added to the enum without a matching i18n entry
        // (the per-locale checks above already enforce coverage, but an
        // explicit count makes the regression intent obvious).
        assertThat(ErrorCode.values())
                .as("ErrorCode enum must have 26 values (NC-400..NC-511)")
                .hasSize(26);
    }
}
