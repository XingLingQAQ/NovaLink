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
 * Proves the target-side moderation notice keys exist in BOTH shipped locale
 * bundles, read directly from the classpath.
 *
 * <p>Why a direct bundle read (not {@code I18n.tr}): the lookup chain falls back
 * en_US → zh_CN → raw key echo, so a key present only in en_US still renders
 * something in Chinese — silently the English copy. Reading each properties file
 * on its own makes a per-locale gap fail here instead of shipping.
 *
 * <p>Mirrors {@code ErrorCodeI18nParityTest}, which established the pattern.
 */
@DisplayName("chat.notice.* moderation keys exist in both locales")
class NoticeKeysI18nParityTest {

    private static final String EN_US_RESOURCE = "lang/messages_en_US.properties";
    private static final String ZH_CN_RESOURCE = "lang/messages_zh_CN.properties";

    /** Every key the KICK/MUTE/BAN/UNBAN adapters render. */
    private static final List<String> NOTICE_KEYS = List.of(
            "chat.notice.kick_title",
            "chat.notice.kick_subtitle",
            "chat.notice.kick_actionbar",
            "chat.notice.mute_title",
            "chat.notice.mute_subtitle",
            "chat.notice.mute_actionbar",
            "chat.notice.ban_title",
            "chat.notice.ban_subtitle",
            "chat.notice.ban_actionbar",
            "chat.notice.unban_title",
            "chat.notice.unban_subtitle",
            "chat.notice.unban_actionbar",
            "notice.channel.global");

    private static ResourceBundle loadDirectly(String classpathResource) throws IOException {
        ClassLoader loader = NoticeKeysI18nParityTest.class.getClassLoader();
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
        for (String key : NOTICE_KEYS) {
            assertThat(bundle.containsKey(key))
                    .as("%s must define %s", classpathResource, key)
                    .isTrue();
            assertThat(bundle.getString(key))
                    .as("%s must define a non-blank %s", classpathResource, key)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("en_US defines every moderation notice key")
    void englishBundleIsComplete() throws IOException {
        assertKeysPresent(EN_US_RESOURCE);
    }

    /**
     * The important one: proves zh_CN carries the ban/unban keys on its own,
     * independently of the en_US fallback.
     */
    @Test
    @DisplayName("zh_CN defines every moderation notice key without relying on the en_US fallback")
    void chineseBundleIsComplete() throws IOException {
        assertKeysPresent(ZH_CN_RESOURCE);
    }

    /**
     * A copy-paste duplicate would make the Chinese bundle silently English for
     * the ban/unban arms if the translator only touched en_US.
     */
    @Test
    @DisplayName("the Chinese ban/unban copy is actually translated")
    void chineseBanCopyIsTranslated() throws IOException {
        ResourceBundle zh = loadDirectly(ZH_CN_RESOURCE);
        ResourceBundle en = loadDirectly(EN_US_RESOURCE);
        for (String key : List.of("chat.notice.ban_title", "chat.notice.ban_subtitle",
                "chat.notice.ban_actionbar", "chat.notice.unban_title",
                "chat.notice.unban_subtitle", "chat.notice.unban_actionbar")) {
            assertThat(zh.getString(key))
                    .as("%s should not be a verbatim copy of the English string", key)
                    .isNotEqualTo(en.getString(key));
        }
    }

    /**
     * The placeholders each adapter passes must match the copy, else the client
     * renders a literal {@code {2}} or drops the duration.
     *
     * <p>Convention (established by the kick/mute keys): {@code {0}} operator,
     * {@code {1}} channel. BAN adds {@code {2}} = duration.
     */
    @Test
    @DisplayName("ban/unban placeholders match what the adapters pass")
    void placeholdersMatchAdapterArguments() throws IOException {
        for (String resource : List.of(EN_US_RESOURCE, ZH_CN_RESOURCE)) {
            ResourceBundle bundle = loadDirectly(resource);
            // KICK/MUTE precedent: operator is {0}, channel is {1}.
            assertThat(bundle.getString("chat.notice.kick_subtitle"))
                    .as("%s kick_subtitle takes (operator, channel)", resource)
                    .contains("{0}").contains("{1}");
            assertThat(bundle.getString("chat.notice.mute_subtitle"))
                    .as("%s mute_subtitle takes (channel, duration)", resource)
                    .contains("{0}").contains("{1}");
            // BAN is passed (operator, channel, duration).
            assertThat(bundle.getString("chat.notice.ban_subtitle"))
                    .as("%s ban_subtitle takes (operator, channel, duration)", resource)
                    .contains("{0}").contains("{1}").contains("{2}");
            assertThat(bundle.getString("chat.notice.ban_actionbar"))
                    .contains("{0}").contains("{1}").contains("{2}");
            // UNBAN is passed (operator, channel) — no duration.
            assertThat(bundle.getString("chat.notice.unban_subtitle"))
                    .as("%s unban_subtitle takes (operator, channel)", resource)
                    .contains("{0}").contains("{1}");
            assertThat(bundle.getString("chat.notice.unban_subtitle"))
                    .as("%s unban has no duration argument", resource)
                    .doesNotContain("{2}");
        }
    }
}
