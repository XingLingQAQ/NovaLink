package com.nova.chat.nukkit.chat;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.level.Level;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Nukkit {@link MessageFormatter} real entry points.
 *
 * <p>Unlike {@code HexColorConversionPropertyTest} (which duplicates the hex
 * algorithm), these tests exercise the actual {@link MessageFormatter} methods —
 * {@code translateColorCodes}, {@code formatChatMessage}, {@code formatSystemMessage}
 * and {@code stripColors} — against a mocked plugin/config/player so the real
 * color translation, placeholder substitution and hex-to-Bedrock approximation
 * paths are covered.
 */
@DisplayName("Nukkit MessageFormatter")
@ExtendWith(MockitoExtension.class)
class MessageFormatterTest {

    @Mock
    private NovaChatNukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Player player;
    @Mock
    private Level level;
    @Mock
    private Server server;

    private MessageFormatter formatter;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getNovaChatConfig()).thenReturn(config);
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getName()).thenReturn("NukkitServer");
        lenient().when(player.getDisplayName()).thenReturn("Steve");
        lenient().when(player.getLevel()).thenReturn(level);
        lenient().when(level.getName()).thenReturn("world");

        formatter = new MessageFormatter(plugin);
    }

    @Nested
    @DisplayName("translateColorCodes")
    class TranslateColorCodes {

        @Test
        @DisplayName("null / empty passthrough")
        void nullAndEmptyPassthrough() {
            assertThat(formatter.translateColorCodes(null)).isNull();
            assertThat(formatter.translateColorCodes("")).isEmpty();
        }

        @Test
        @DisplayName("legacy & codes become section codes")
        void legacyCodesTranslated() {
            String result = formatter.translateColorCodes("&aHello &bWorld");
            assertThat(result).contains("§a", "§b");
            assertThat(result).doesNotContain("&a", "&b");
        }

        @Test
        @DisplayName("hex &#RRGGBB approximates to nearest Bedrock section code")
        void hexApproximated() {
            String result = formatter.translateColorCodes("&#FF0000red");
            // FF0000 (255,0,0) is closest to §4 (dark red, 170,0,0) or §c (red, 255,85,85)
            // depending on tie-breaks; both are valid red mappings on the Bedrock palette.
            assertThat(result).startsWith("§");
            assertThat(result.substring(1, 2)).isIn("4", "c");
            assertThat(result).doesNotContain("&#FF0000");
        }

        @Test
        @DisplayName("hex and legacy codes mix in one pass")
        void hexAndLegacyMix() {
            String result = formatter.translateColorCodes("&#00FF00green &etext");
            assertThat(result).contains("§");
            assertThat(result).doesNotContain("&#", "&e");
        }
    }

    @Nested
    @DisplayName("formatChatMessage")
    class FormatChatMessage {

        @Test
        @DisplayName("substitutes all standard placeholders and applies color codes")
        void substitutesPlaceholders() {
            String template = "§7[{channel_name}] {player}§f: {message} (&aworld={world}&r)";
            when(config.getChannelFormat("global")).thenReturn(template);

            String result = formatter.formatChatMessage(player, "global", "Global", "Steve", "hi there");

            assertThat(result).contains("Steve", "hi there", "Global", "world");
            // Color codes are translated (&a -> §a) by the real formatter.
            assertThat(result).contains("§a");
            assertThat(result).doesNotContain("&a");
            // Placeholders are gone.
            assertThat(result).doesNotContain("{player}", "{message}", "{channel_name}", "{world}");
        }

        @Test
        @DisplayName("null channelName falls back to channel id")
        void nullChannelNameFallsBackToId() {
            when(config.getChannelFormat("trade")).thenReturn("[{channel}] {player}: {message}");

            String result = formatter.formatChatMessage(player, "trade", null, "Alex", "selling");

            assertThat(result).contains("[trade]", "Alex", "selling");
            assertThat(result).doesNotContain("{channel}", "{player}", "{message}");
        }

        @Test
        @DisplayName("null player still formats with senderName fallback for display_name")
        void nullPlayerUsesSenderNameForDisplayName() {
            when(config.getChannelFormat("global")).thenReturn("<{display_name}> {message}");

            String result = formatter.formatChatMessage(null, "global", "Global", "Console", "hello");

            assertThat(result).contains("<Console>", "hello");
            assertThat(result).doesNotContain("{display_name}", "{message}");
        }
    }

    @Nested
    @DisplayName("formatSystemMessage")
    class FormatSystemMessage {

        @Test
        @DisplayName("error type wraps message with prefix + error format")
        void errorTypeWrapped() {
            when(config.getPrefix()).thenReturn("§8[§bNC§8]§r ");
            when(config.getErrorFormat()).thenReturn("§c{message}");
            when(config.getSuccessFormat()).thenReturn("§a{message}");

            String result = formatter.formatSystemMessage("error", "boom");

            assertThat(result).contains("§8[§bNC§8]§r", "§c", "boom");
            assertThat(result).doesNotContain("{message}");
        }

        @Test
        @DisplayName("success type wraps message with prefix + success format")
        void successTypeWrapped() {
            when(config.getPrefix()).thenReturn("§8[§bNC§8]§r ");
            when(config.getErrorFormat()).thenReturn("§c{message}");
            when(config.getSuccessFormat()).thenReturn("§a{message}");

            String result = formatter.formatSystemMessage("success", "done");

            assertThat(result).contains("§8[§bNC§8]§r", "§a", "done");
            assertThat(result).doesNotContain("{message}");
        }
    }

    @Nested
    @DisplayName("stripColors")
    class StripColors {

        @Test
        @DisplayName("null returns null")
        void nullReturnsNull() {
            assertThat(formatter.stripColors(null)).isNull();
        }

        @Test
        @DisplayName("removes section and ampersand color codes")
        void removesColorCodes() {
            String stripped = formatter.stripColors("§aHello §b&cWorld");
            assertThat(stripped).doesNotContain("§", "&c", "&b");
            assertThat(stripped).contains("Hello", "World");
        }

        @Test
        @DisplayName("removes hex color codes")
        void removesHexCodes() {
            String stripped = formatter.stripColors("&#FF0000colored text");
            assertThat(stripped).doesNotContain("&#", "§");
            assertThat(stripped).contains("colored text");
        }
    }
}
