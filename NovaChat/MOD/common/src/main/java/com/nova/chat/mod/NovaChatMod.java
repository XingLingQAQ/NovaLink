package com.nova.chat.mod;

import com.nova.chat.client.channel.ConfigSyncHandlerRegistrar;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.chat.MessageFormatter;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.ModServices;
import com.nova.chat.mod.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NovaChat Mod - Common module entry point.
 *
 * <p>This module contains platform-independent code shared across all mod loaders
 * (fabric / forge / neoforge / quilt) and depends on {@code novachat-client-core},
 * making it a sibling of the seven server-side platforms (bukkit / velocity /
 * bungee / nukkit / folia / pnx / sponge) rather than a self-rolled thin layer.
 *
 * <p>The static {@link #bootstrap(Platform, ModConfig, String)} builds the shared
 * runtime — {@link NetworkClient} (extends {@code AbstractPlatformNetworkClient}),
 * {@link ChatInterceptor} (uses {@code PlayerStateStore} +
 * {@code ChannelResponseDispatcher} + {@code MentionNotifier} + {@code I18n}),
 * {@link ChannelCommandService} and {@link KnownChannelRegistry} — and returns a
 * {@link ModServices} holder each loader wires into its command registrar and
 * lifecycle hooks.
 */
public class NovaChatMod {
    private static final Logger LOGGER = LoggerFactory.getLogger(NovaChatMod.class);

    public static final String MOD_ID = "novachat";
    public static final String MOD_NAME = "NovaChat";
    public static final String MOD_VERSION = "1.0.0";

    /**
     * Legacy no-arg init kept for backward compatibility with loader entry points
     * that just log the version banner.
     */
    public static void init() {
        LOGGER.info("Initializing NovaChat Mod v{}", MOD_VERSION);
    }

    /**
     * Builds the shared mod runtime for a loader.
     *
     * <p>Steps:
     * <ol>
     *   <li>Apply the configured default locale to the shared i18n service.</li>
     *   <li>Construct the {@link NetworkClient} facade over {@code CoreNetworkClient}
     *       via the platform scheduler/logger bridges.</li>
     *   <li>Register the ConfigSync handler on the shared known-channel registry.</li>
     *   <li>Construct the {@link ChatInterceptor} (registers incoming chat/response/
     *       mention handlers on the network client).</li>
     *   <li>Build {@link ChannelCommandService#forPlatform} wired to the live client.</li>
     * </ol>
     *
     * <p>The caller is responsible for connecting the network client and registering
     * the chat listener + commands on its own lifecycle events.
     *
     * @param platform the mod platform abstraction
     * @param config   the loaded mod configuration
     * @param clientId the backend client id / username (from config); used for
     *                 ConfigSync per-client channel filtering
     * @return the shared services holder
     */
    public static ModServices bootstrap(Platform platform, ModConfig config, String clientId) {
        return bootstrap(platform, config, clientId, null);
    }

    /**
     * Builds the shared mod runtime for a loader, with an ignore-list data
     * directory.
     *
     * <p>Same as {@link #bootstrap(Platform, ModConfig, String)}, plus the shared
     * {@code IgnoreListService} (/nc ignore). When {@code dataDirectory} is
     * non-null, ignore lists persist to {@code ignore-lists.json} in it; loaders
     * should pass their config/data dir and call
     * {@code services.getIgnoreListService().close()} on server shutdown to
     * flush pending writes.
     *
     * @param platform      the mod platform abstraction
     * @param config        the loaded mod configuration
     * @param clientId      the backend client id / username (from config)
     * @param dataDirectory the ignore-list persistence directory, or null for
     *                      in-memory only
     * @return the shared services holder
     */
    public static ModServices bootstrap(Platform platform, ModConfig config, String clientId,
                                        java.nio.file.Path dataDirectory) {
        // i18n default locale
        I18n.setDefaultLocale(LocaleResolver.parseOrDefault(
                config.getChat().getLocale(), LocaleResolver.ROOT_LOCALE));

        // Network client over the shared core
        NetworkClient networkClient = new NetworkClient(platform, config);

        // ConfigSync → known-channel registry
        KnownChannelRegistry knownChannelRegistry = new KnownChannelRegistry();
        ConfigSyncHandlerRegistrar.register(networkClient, knownChannelRegistry, clientId);

        // Per-player ignore lists (/nc ignore)
        com.nova.chat.client.ignore.IgnoreListService ignoreListService =
                new com.nova.chat.client.ignore.IgnoreListService();
        if (dataDirectory != null) {
            ignoreListService.setDataDirectory(dataDirectory);
        }

        // Chat interceptor (registers incoming handlers)
        String defaultChannel = config.getChat().getDefaultChannel();
        String defaultFormat = config.getFormats().getOrDefault(defaultChannel, "{player}: {message}");
        MessageFormatter messageFormatter = new MessageFormatter(config.getFormats(), defaultFormat);
        ChatInterceptor chatInterceptor = new ChatInterceptor(platform, networkClient, config,
                messageFormatter, knownChannelRegistry, ignoreListService);

        // Shared channel command service
        PlatformType commonPlatformType = platform.getPlatformType().toCommon();
        ChannelCommandService channelCommandService = ChannelCommandService.forPlatform(
                () -> networkClient, commonPlatformType);

        return new ModServices(config, networkClient, chatInterceptor,
                channelCommandService, knownChannelRegistry, ignoreListService);
    }
}
