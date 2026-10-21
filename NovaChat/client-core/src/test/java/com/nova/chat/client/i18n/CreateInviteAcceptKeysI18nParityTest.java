package com.nova.chat.client.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the CREATE / INVITE / ACCEPT render keys exist in BOTH shipped locale
 * bundles, read directly from the classpath.
 *
 * <p>Why a direct bundle read (not {@code I18n.tr}): the lookup chain falls back
 * en_US → zh_CN → raw key echo, so a key present only in en_US still renders
 * something in Chinese — silently the English copy. Reading each properties file
 * on its own makes a per-locale gap fail here instead of shipping.
 *
 * <p>Mirrors {@code NoticeKeysI18nParityTest}, which established the pattern.
 */
@DisplayName("chat.create.* / chat.invite.* / chat.accept.* success keys exist in both locales")
class CreateInviteAcceptKeysI18nParityTest {

    private static final String EN_US_RESOURCE = "lang/messages_en_US.properties";
    private static final String ZH_CN_RESOURCE = "lang/messages_zh_CN.properties";

    /** Every key the CREATE / INVITE / ACCEPT success arms render. */
    private static final List<String> SUCCESS_KEYS = List.of(
            "chat.create.result",
            "chat.create.password_saved",
            "chat.invite.code",
            "chat.invite.expires_in",
            "chat.accept.accepted",
            // Generic fallbacks the adapters use when the response carries no
            // usable id/password/code.
            "chat.action.create_processed",
            "chat.action.invite_processed",
            "chat.action.accepted");

    private static ResourceBundle loadDirectly(String classpathResource) throws IOException {
        ClassLoader loader = CreateInviteAcceptKeysI18nParityTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(classpathResource)) {
            assertThat(stream)
                    .as("bundle missing on the test classpath: %s", classpathResource)
                    .isNotNull();
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new PropertyResourceBundle(reader);
            }
        }
    }

    private static void assertKeysPresent(String classpathResource) throws IOException {
        ResourceBundle bundle = loadDirectly(classpathResource);
        for (String key : SUCCESS_KEYS) {
            assertThat(bundle.containsKey(key))
                    .as("%s must define %s", classpathResource, key)
                    .isTrue();
            assertThat(bundle.getString(key))
                    .as("%s must define a non-blank %s", classpathResource, key)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("en_US defines every CREATE/INVITE/ACCEPT success key")
    void englishBundleIsComplete() throws IOException {
        assertKeysPresent(EN_US_RESOURCE);
    }

    /**
     * The important one: proves zh_CN carries the keys on its own, independently
     * of the en_US fallback.
     */
    @Test
    @DisplayName("zh_CN defines every CREATE/INVITE/ACCEPT success key without relying on the en_US fallback")
    void chineseBundleIsComplete() throws IOException {
        assertKeysPresent(ZH_CN_RESOURCE);
    }

    /**
     * A copy-paste duplicate would make the Chinese bundle silently English if
     * the translator only touched en_US.
     */
    @Test
    @DisplayName("the Chinese copy is actually translated")
    void chineseCopyIsTranslated() throws IOException {
        ResourceBundle zh = loadDirectly(ZH_CN_RESOURCE);
        ResourceBundle en = loadDirectly(EN_US_RESOURCE);
        for (String key : SUCCESS_KEYS) {
            assertThat(zh.getString(key))
                    .as("%s should not be a verbatim copy of the English string", key)
                    .isNotEqualTo(en.getString(key));
        }
    }

    /**
     * The placeholders each renderer passes must match the copy, else the client
     * renders a literal {@code {1}} or drops the password.
     *
     * <p>{@code chat.create.result} takes (channelId, password);
     * {@code chat.invite.code} takes (code) twice — the runnable hint repeats it;
     * {@code chat.accept.accepted} takes (channelId); {@code chat.invite.expires_in}
     * takes (duration phrase).
     */
    @Test
    @DisplayName("placeholders match what the renderers pass")
    void placeholdersMatchRendererArguments() throws IOException {
        for (String resource : List.of(EN_US_RESOURCE, ZH_CN_RESOURCE)) {
            ResourceBundle bundle = loadDirectly(resource);
            assertThat(bundle.getString("chat.create.result"))
                    .as("%s create.result takes (channelId, password)", resource)
                    .contains("{0}").contains("{1}");
            assertThat(bundle.getString("chat.invite.code"))
                    .as("%s invite.code repeats the code in the accept hint", resource)
                    .contains("{0}");
            assertThat(bundle.getString("chat.accept.accepted"))
                    .as("%s accept.accepted takes (channelId)", resource)
                    .contains("{0}");
            assertThat(bundle.getString("chat.invite.expires_in"))
                    .as("%s invite.expires_in takes (duration)", resource)
                    .contains("{0}");
            // The generic fallbacks take no arguments.
            for (String key : List.of("chat.create.password_saved",
                    "chat.action.create_processed",
                    "chat.action.invite_processed",
                    "chat.action.accepted")) {
                assertThat(bundle.getString(key))
                        .as("%s %s takes no placeholders", resource, key)
                        .doesNotContain("{0}");
            }
        }
    }
}
