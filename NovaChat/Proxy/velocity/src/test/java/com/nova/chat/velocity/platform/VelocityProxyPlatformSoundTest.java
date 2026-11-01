package com.nova.chat.velocity.platform;

import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.sound.Sound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Velocity platform sound dispatch")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VelocityProxyPlatformSoundTest {

    @Mock
    private NovaChatVelocity plugin;
    @Mock
    private ProxyServer proxyServer;
    @Mock
    private Player player;

    private VelocityProxyPlatform platform;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(proxyServer);
        platform = new VelocityProxyPlatform(plugin);
    }

    @Test
    @DisplayName("the Bukkit-style default becomes a valid resource path")
    void defaultSoundBecomesAResourcePath() {
        platform.playSound(
                new VelocityProxyPlayer(player), MentionNotifier.DEFAULT_SOUND, 1f, 1f);

        assertThat(capturedSoundKey())
                .as("the client only resolves dotted resource paths; the enum spelling is silent")
                .isEqualTo("minecraft:entity.experience_orb.pickup");
    }

    @Test
    @DisplayName("an already-valid resource path is passed through untouched")
    void resourcePathPassesThrough() {
        platform.playSound(new VelocityProxyPlayer(player), "block.note_block.pling", 1f, 1f);

        assertThat(capturedSoundKey()).isEqualTo("minecraft:block.note_block.pling");
    }

    @Test
    @DisplayName("a minecraft: prefix on a resource path is not doubled")
    void minecraftPrefixIsStripped() {
        platform.playSound(
                new VelocityProxyPlayer(player), "minecraft:ui.toast.challenge_complete", 1f, 1f);

        assertThat(capturedSoundKey()).isEqualTo("minecraft:ui.toast.challenge_complete");
    }

    @Test
    @DisplayName("an unmappable enum-style id falls back to the mention default")
    void unknownEnumIdFallsBack() {
        platform.playSound(new VelocityProxyPlayer(player), "SOME_FUTURE_SOUND", 1f, 1f);

        assertThat(capturedSoundKey())
                .as("a made-up path would be silently ignored; the default at least plays")
                .isEqualTo("minecraft:entity.experience_orb.pickup");
    }

    @Test
    @DisplayName("mention notifications use Velocity's self-emitter overload")
    void mentionUsesSelfEmitter() {
        platform.notifyMention(new VelocityProxyPlayer(player), "&eAlice", "Mentioned you");

        Sound sound = capturedSound();
        assertThat(sound.name().asString()).isEqualTo("minecraft:entity.experience_orb.pickup");
        assertThat(sound.source()).isEqualTo(Sound.Source.PLAYER);
        assertThat(sound.volume()).isEqualTo(1f);
        assertThat(sound.pitch()).isEqualTo(1f);
    }

    @Test
    @DisplayName("the emitter overload preserves volume and pitch")
    void preservesPlaybackParameters() {
        platform.playSound(new VelocityProxyPlayer(player), MentionNotifier.DEFAULT_SOUND, 0.5f, 1.25f);

        Sound sound = capturedSound();
        assertThat(sound.source()).isEqualTo(Sound.Source.PLAYER);
        assertThat(sound.volume()).isEqualTo(0.5f);
        assertThat(sound.pitch()).isEqualTo(1.25f);
    }

    private String capturedSoundKey() {
        return capturedSound().name().asString();
    }

    private Sound capturedSound() {
        ArgumentCaptor<Sound> sound = ArgumentCaptor.forClass(Sound.class);
        verify(player).playSound(sound.capture(), eq(Sound.Emitter.self()));
        verify(player, never()).playSound(org.mockito.ArgumentMatchers.any(Sound.class));
        return sound.getValue();
    }
}
