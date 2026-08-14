package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.client.ignore.IgnoreListService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IgnoreCommandService}: argument validation, receipt
 * copy under both locales, list rendering and the limit message.
 */
@DisplayName("IgnoreCommandService")
class IgnoreCommandServiceTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private IgnoreListService service;
    private Locale savedDefault;

    @BeforeEach
    void setUp() {
        service = new IgnoreListService();
        savedDefault = I18n.getDefaultLocale();
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(savedDefault);
    }

    @Test
    @DisplayName("missing argument renders the usage line (ignore and unignore)")
    void missingArgumentShowsUsage() {
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[0]))
                .containsExactly("&7用法: /nc ignore <玩家名> | /nc ignore list | /nc unignore <玩家名>");
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", null))
                .hasSize(1).first().asString().contains("用法");
        assertThat(IgnoreCommandService.unignore(service, PLAYER, new String[0]))
                .hasSize(1).first().asString().contains("用法");
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"  "}))
                .hasSize(1).first().asString().contains("用法");
    }

    @Test
    @DisplayName("successful ignore/unignore render localized receipts with the target name")
    void addAndRemoveReceipts() {
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"Steve"}))
                .containsExactly("&7已屏蔽玩家 &eSteve");
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"steve"}))
                .containsExactly("&7玩家 &esteve &7已在屏蔽列表中");
        assertThat(IgnoreCommandService.unignore(service, PLAYER, new String[]{"STEVE"}))
                .containsExactly("&7已解除屏蔽玩家 &eSTEVE");
        assertThat(IgnoreCommandService.unignore(service, PLAYER, new String[]{"Steve"}))
                .containsExactly("&7玩家 &eSteve &7不在屏蔽列表中");
    }

    @Test
    @DisplayName("ignoring yourself renders the self-check message")
    void cannotIgnoreSelf() {
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"alice"}))
                .containsExactly("&c不能屏蔽自己");
    }

    @Test
    @DisplayName("ignore list renders empty prompt, then header + sorted items")
    void listRendering() {
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"list"}))
                .containsExactly("&7你没有屏蔽任何玩家");

        IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"Zed"});
        IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"Bob"});

        List<String> lines = IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"LIST"});
        assertThat(lines).containsExactly(
                "&6=== 已屏蔽玩家（共 2 个）===",
                "&7- &fbob",
                "&7- &fzed");
    }

    @Test
    @DisplayName("limit message carries the configured maximum")
    void limitMessage() {
        for (int i = 0; i < IgnoreListService.MAX_IGNORES_PER_PLAYER; i++) {
            service.ignore(PLAYER, "Alice", "p" + i);
        }
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"Extra"}))
                .containsExactly("&c屏蔽列表已满（上限 100 个），请先解除部分屏蔽");
    }

    @Test
    @DisplayName("en_US locale renders the English copy")
    void englishLocale() {
        I18n.setDefaultLocale(Locale.US);
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"Steve"}))
                .containsExactly("&7Ignored player &eSteve");
        assertThat(IgnoreCommandService.unignore(service, PLAYER, new String[]{"Steve"}))
                .containsExactly("&7Unignored player &eSteve");
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"alice"}))
                .containsExactly("&cYou cannot ignore yourself");
        assertThat(IgnoreCommandService.ignore(service, PLAYER, "Alice", new String[]{"list"}))
                .containsExactly("&7You are not ignoring any player");
    }
}
