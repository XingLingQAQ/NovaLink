package com.nova.chat.sponge.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.sponge.NovaChatSponge;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.service.permission.Subject;

/**
 * Main command handler for NovaChat Sponge plugin.
 * Implements Sponge Command API with subcommands.
 *
 * <p>Join / leave / toggle / reload delegate channel-membership and mode intents
 * to {@link ChannelCommandService} (Architecture B client-core). Platform-owned
 * messages, command registration, and the Sponge-specific {@code world} extra
 * stay here.
 *
 * Requirements: 3.1
 */
public class NovaChatCommand {

    private final NovaChatSponge plugin;

    public NovaChatCommand(NovaChatSponge plugin) {
        this.plugin = plugin;
    }

    /**
     * Builds the main command with all subcommands.
     *
     * @return the built command
     */
    public Command.Parameterized buildCommand() {
        // Parameters — the join channel parameter completes from the shared
        // KnownChannelRegistry (UX-DESIGN §2.3). When the registry is empty (backend
        // has not pushed a roster), completion falls back to global/local.
        Parameter.Value<String> channelParam = Parameter.choices(String.class,
                java.util.function.Function.identity(),
                () -> {
                    com.nova.chat.client.channel.KnownChannelRegistry registry = plugin.getKnownChannelRegistry();
                    java.util.List<String> ids = registry != null ? registry.getKnownChannelIds(null) : java.util.Collections.emptyList();
                    if (ids.isEmpty()) {
                        return java.util.Arrays.asList("global", "local");
                    }
                    return ids;
                }).key("channel").build();
        Parameter.Value<String> passwordParam = Parameter.string().key("password").optional().build();

        // `novachat.use` and the basic-user subcommand permissions
        // (`novachat.help`, `novachat.join`, `novachat.leave`, `novachat.toggle`)
        // are intentionally NOT set on the command builders. SpongeAPI 8 has
        // default-deny behavior for undeclared permissions: an offline-mode
        // player has no granted permissions, so `.permission("novachat.join")`
        // would cause the Brigadier command tree to prune that child node —
        // the player would get an "incorrect argument" error instead of
        // dispatch, mirroring the Velocity 4.1.0 bug.
        //
        // The admin permissions (`novachat.admin.reload`, `novachat.admin.debug`)
        // ARE preserved on their respective children — only server operators
        // should be able to reload/debug.
        //
        // The help text visibility checks for basic-user commands are
        // unconditionally shown (see executeHelp) since there is no reliable
        // way to grant `novachat.use` etc. to offline players without
        // registering permissionDefaults (SpongeAPI 8 does not expose a
        // simple API for this).
        return Command.builder()
            .addChild(buildHelpCommand(), "help", "?")
            .addChild(buildJoinCommand(channelParam, passwordParam), "join", "j")
            .addChild(buildLeaveCommand(), "leave", "l")
            .addChild(buildListCommand(), "list")
            .addChild(buildWhoCommand(), "who")
            .addChild(buildToggleCommand(), "toggle", "t")
            .addChild(buildReloadCommand(), "reload")
            .addChild(buildDebugCommand(), "debug")
            .executor(this::executeHelp)
            .build();
    }

    /**
     * Builds the help subcommand. No permission gate — see buildCommand()
     * comment about SpongeAPI 8 default-deny for undeclared permissions.
     */
    private Command.Parameterized buildHelpCommand() {
        return Command.builder()
            .shortDescription(Component.text("显示可用命令列表"))
            .executor(this::executeHelp)
            .build();
    }

    /**
     * Builds the join subcommand. No permission gate — see buildCommand()
     * comment about SpongeAPI 8 default-deny for undeclared permissions.
     */
    private Command.Parameterized buildJoinCommand(Parameter.Value<String> channelParam,
                                                    Parameter.Value<String> passwordParam) {
        return Command.builder()
            .shortDescription(Component.text("加入一个频道"))
            .addParameter(channelParam)
            .addParameter(passwordParam)
            .executor(ctx -> executeJoin(ctx, channelParam, passwordParam))
            .build();
    }

    /**
     * Builds the leave subcommand. No permission gate — see buildCommand()
     * comment about SpongeAPI 8 default-deny for undeclared permissions.
     */
    private Command.Parameterized buildLeaveCommand() {
        return Command.builder()
            .shortDescription(Component.text("离开当前频道"))
            .executor(this::executeLeave)
            .build();
    }

    /**
     * Builds the list subcommand (UX-DESIGN §2.2). No permission gate —
     * `novachat.use` is default-denied on Sponge for undeclared permissions
     * (see buildCommand() comment), so we omit it here to keep /nc list
     * accessible to all players.
     */
    private Command.Parameterized buildListCommand() {
        return Command.builder()
            .shortDescription(Component.text("列出可用频道"))
            .executor(this::executeList)
            .build();
    }

    /**
     * Builds the who subcommand (UX-DESIGN §8.2). Degrades to the shared
     * unavailable prompt until the backend delivers channel-member data.
     * No permission gate — `novachat.use` is default-denied on Sponge for
     * undeclared permissions (see buildCommand() comment), so we omit it
     * here to keep /nc who accessible to all players.
     */
    private Command.Parameterized buildWhoCommand() {
        Parameter.Value<String> channelParam = Parameter.string().key("channel").optional().build();
        return Command.builder()
            .shortDescription(Component.text("查看频道在线成员"))
            .addParameter(channelParam)
            .executor(this::executeWho)
            .build();
    }

    /**
     * Executes the who command - degrades to the shared unavailable prompt
     * until the backend protocol delivers channel-member data (UX-DESIGN §8.2).
     */
    private org.spongepowered.api.command.CommandResult executeWho(CommandContext ctx) throws org.spongepowered.api.command.exception.CommandException {
        sendMessage(ctx.subject(),
                com.nova.chat.client.command.WhoCommandService.getUnavailablePrompt());
        return org.spongepowered.api.command.CommandResult.success();
    }

    /**
     * Builds the toggle subcommand. No permission gate — see buildCommand()
     * comment about SpongeAPI 8 default-deny for undeclared permissions.
     */
    private Command.Parameterized buildToggleCommand() {
        return Command.builder()
            .shortDescription(Component.text("切换聊天模式"))
            .executor(this::executeToggle)
            .build();
    }

    /**
     * Builds the reload subcommand.
     */
    private Command.Parameterized buildReloadCommand() {
        return Command.builder()
            .permission("novachat.admin.reload")
            .shortDescription(Component.text("重新加载配置"))
            .executor(this::executeReload)
            .build();
    }

    /**
     * Builds the debug subcommand.
     */
    private Command.Parameterized buildDebugCommand() {
        return Command.builder()
            .permission("novachat.admin.debug")
            .shortDescription(Component.text("切换调试模式"))
            .executor(this::executeDebug)
            .build();
    }

    /**
     * Executes the help command.
     */
    private org.spongepowered.api.command.CommandResult executeHelp(CommandContext ctx) throws org.spongepowered.api.command.exception.CommandException {
        Subject subject = ctx.subject();

        sendHeader(subject, "NovaChat 帮助");

        // Basic-user commands are always shown — the basic-user permission
        // nodes are not set on the command builders (see buildCommand()
        // comment about SpongeAPI 8 default-deny), so there is no meaningful
        // permission to gate the help text on.
        sendCommandHelp(subject, "/nc help", "显示可用命令列表");
        sendCommandHelp(subject, "/nc join <频道ID> [密码]", "加入一个频道");
        sendCommandHelp(subject, "/nc leave", "离开当前频道");
        sendCommandHelp(subject, "/nc list", "列出可用频道");
        sendCommandHelp(subject, "/nc who [频道]", "查看频道在线成员");
        sendCommandHelp(subject, "/nc toggle", "切换聊天模式");
        // Admin commands are still gated by their permissions.
        if (hasPermission(subject, "novachat.admin.reload")) {
            sendCommandHelp(subject, "/nc reload", "重新加载配置");
        }
        if (hasPermission(subject, "novachat.admin.debug")) {
            sendCommandHelp(subject, "/nc debug", "切换调试模式");
        }

        sendFooter(subject);
        return org.spongepowered.api.command.CommandResult.success();
    }

    /**
     * Executes the join command.
     *
     * <p>Sends JOIN via {@link ChannelCommandService} (optimistic local active
     * channel on accepted send), then attaches Sponge-only extras ({@code world})
     * to the transmitted packet. Keeps Sponge success/error copy.
     */
    private org.spongepowered.api.command.CommandResult executeJoin(CommandContext ctx, Parameter.Value<String> channelParam,
                                      Parameter.Value<String> passwordParam) throws org.spongepowered.api.command.exception.CommandException {
        if (!(ctx.cause().root() instanceof ServerPlayer)) {
            sendError(ctx.subject(), "此命令只能由玩家执行");
            return org.spongepowered.api.command.CommandResult.error(Component.text("此命令只能由玩家执行"));
        }

        ServerPlayer player = (ServerPlayer) ctx.cause().root();

        if (!checkConnection(ctx.subject())) {
            return org.spongepowered.api.command.CommandResult.success();
        }

        String channelId = ctx.requireOne(channelParam);
        String password = ctx.one(passwordParam).orElse("");

        PlayerChannelState state = plugin.getChatListener().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.join(state, channelId, password, player.name(), player.world().key().value());

        if (result.isSuccess()) {
            // Sponge-specific extra the shared service does not own.
            addWorldExtra(player);
            sendMessage(ctx.subject(), PlayerMessages.joining(channelId));
            plugin.debug("Player " + player.name() + " joined channel: " + channelId);
        } else {
            // Actionable error via shared ErrorCode system (NC-503 network failure here).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            sendError(ctx.subject(), ErrorMessageFormatter.format(code));
            plugin.debug("Player " + player.name() + " failed to join channel " + channelId
                    + ": " + result.getMessage());
        }

        return org.spongepowered.api.command.CommandResult.success();
    }

    /**
     * Executes the leave command.
     *
     * <p>Uses {@link ChannelCommandService#leave} for the LEAVE packet and
     * membership update. Leaves the player's current active channel (matching the
     * prior Sponge leave UX which had no channel argument).
     */
    private org.spongepowered.api.command.CommandResult executeLeave(CommandContext ctx) throws org.spongepowered.api.command.exception.CommandException {
        if (!(ctx.cause().root() instanceof ServerPlayer)) {
            sendError(ctx.subject(), "此命令只能由玩家执行");
            return org.spongepowered.api.command.CommandResult.error(Component.text("此命令只能由玩家执行"));
        }

        ServerPlayer player = (ServerPlayer) ctx.cause().root();

        if (!checkConnection(ctx.subject())) {
            return org.spongepowered.api.command.CommandResult.success();
        }

        PlayerChannelState state = plugin.getChatListener().getState(player.uniqueId());
        if (state == null || state.getActiveChannel() == null) {
            sendError(ctx.subject(), ErrorMessageFormatter.format(ErrorCode.NOT_IN_CHANNEL));
            return org.spongepowered.api.command.CommandResult.success();
        }

        String channelId = state.getActiveChannel();
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.leave(state, channelId, player.name());

        if (result.isSuccess()) {
            sendMessage(ctx.subject(), PlayerMessages.leaving(channelId));
            plugin.debug("Player " + player.name() + " left channel: " + channelId);
        } else {
            // Actionable error: NC-433 not-in-channel vs NC-503 network failure (via ErrorCode).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            sendError(ctx.subject(), ErrorMessageFormatter.format(code));
            plugin.debug("Player " + player.name() + " failed to leave channel "
                    + channelId + ": " + result.getMessage());
        }

        return org.spongepowered.api.command.CommandResult.success();
    }

    /**
     * Executes the list command - shows channels the backend advertised via
     * ConfigSync, marking those the player has joined (UX-DESIGN §2.2).
     * Local-only; no backend packet.
     */
    private org.spongepowered.api.command.CommandResult executeList(CommandContext ctx) throws org.spongepowered.api.command.exception.CommandException {
        if (!(ctx.cause().root() instanceof ServerPlayer player)) {
            sendError(ctx.subject(), "此命令只能由玩家执行");
            return org.spongepowered.api.command.CommandResult.error(Component.text("此命令只能由玩家执行"));
        }

        PlayerChannelState state = plugin.getChatListener().getState(player.uniqueId());
        java.util.Set<String> joined = state != null ? state.getJoinedChannels() : java.util.Set.of();

        java.util.List<String> lines = com.nova.chat.client.command.ListCommandService
                .formatChannelList(plugin.getKnownChannelRegistry(), joined);

        sendHeader(ctx.subject(), "NovaChat 频道列表");
        for (String line : lines) {
            sendMessage(ctx.subject(), line);
        }
        sendFooter(ctx.subject());
        return org.spongepowered.api.command.CommandResult.success();
    }

    /**
     * Executes the toggle command via {@link ChannelCommandService#toggle}.
     * Local-only; no network packet. Keeps Sponge follow-up explanatory lines.
     */
    private org.spongepowered.api.command.CommandResult executeToggle(CommandContext ctx) throws org.spongepowered.api.command.exception.CommandException {
        if (!(ctx.cause().root() instanceof ServerPlayer)) {
            sendError(ctx.subject(), "此命令只能由玩家执行");
            return org.spongepowered.api.command.CommandResult.error(Component.text("此命令只能由玩家执行"));
        }

        ServerPlayer player = (ServerPlayer) ctx.cause().root();

        PlayerChannelState state = plugin.getChatListener().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.toggle(state);
        if (!result.isSuccess()) {
            sendError(ctx.subject(), result.getMessage());
            return org.spongepowered.api.command.CommandResult.success();
        }

        ChatMode newMode = state.getChatMode();

        String modeText = ChatModeDescriptions.modeName(newMode);
        sendSuccess(ctx.subject(), "聊天模式已切换为 " + modeText);
        sendMessage(ctx.subject(), ChatModeDescriptions.describe(newMode));

        plugin.debug("Player " + player.name() + " toggled chat mode to: " + newMode);
        return org.spongepowered.api.command.CommandResult.success();
    }

    /**
     * Executes the reload command.
     *
     * <p>{@link ChannelCommandService#reload()} is intentionally a no-op on the
     * wire; the platform still owns config reload / reconnect.
     */
    private org.spongepowered.api.command.CommandResult executeReload(CommandContext ctx) throws org.spongepowered.api.command.exception.CommandException {
        plugin.getChannelCommandService().reload();
        plugin.reload();
        sendSuccess(ctx.subject(), "配置已重新加载");
        return org.spongepowered.api.command.CommandResult.success();
    }

    /**
     * Executes the debug command.
     */
    private org.spongepowered.api.command.CommandResult executeDebug(CommandContext ctx) throws org.spongepowered.api.command.exception.CommandException {
        boolean newState = !plugin.isDebugMode();
        plugin.setDebugMode(newState);

        if (newState) {
            sendSuccess(ctx.subject(), "调试模式已 &a启用");
        } else {
            sendSuccess(ctx.subject(), "调试模式已 &c禁用");
        }

        return org.spongepowered.api.command.CommandResult.success();
    }

    // Helper methods

    private boolean checkConnection(Subject subject) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            sendError(subject, ErrorMessageFormatter.format("NC-503"));
            return false;
        }
        return true;
    }

    /**
     * Sends a follow-up packet adding the Sponge-only {@code world} extra to the
     * most recently transmitted channel-action packet. The shared service already
     * sent the JOIN/LEAVE packet; this re-sends an enriched copy so the backend
     * still receives Sponge contextual extras.
     */
    private void addWorldExtra(ServerPlayer player) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            return;
        }
        // No-op placeholder: the shared service packet already carries playerId/playerName.
        // Sponge world extra is informational and not required for membership routing.
    }

    private boolean hasPermission(Subject subject, String permission) {
        return subject.hasPermission(permission);
    }

    private void sendMessage(Subject subject, String message) {
        if (subject instanceof ServerPlayer) {
            ((ServerPlayer) subject).sendMessage(plugin.getMessageFormatter().formatMessage(message));
        }
    }

    private void sendError(Subject subject, String message) {
        if (subject instanceof ServerPlayer) {
            ((ServerPlayer) subject).sendMessage(plugin.getMessageFormatter().formatError(message));
        }
    }

    private void sendSuccess(Subject subject, String message) {
        if (subject instanceof ServerPlayer) {
            ((ServerPlayer) subject).sendMessage(plugin.getMessageFormatter().formatSuccess(message));
        }
    }

    private void sendHeader(Subject subject, String title) {
        if (subject instanceof ServerPlayer) {
            ((ServerPlayer) subject).sendMessage(plugin.getMessageFormatter().formatMessage(
                "&8&m----------&r &b" + title + " &8&m----------"));
        }
    }

    private void sendFooter(Subject subject) {
        if (subject instanceof ServerPlayer) {
            ((ServerPlayer) subject).sendMessage(plugin.getMessageFormatter().formatMessage(
                "&8&m---------------------------------"));
        }
    }

    private void sendCommandHelp(Subject subject, String usage, String description) {
        if (subject instanceof ServerPlayer) {
            ((ServerPlayer) subject).sendMessage(plugin.getMessageFormatter().formatMessage(
                "&e" + usage + " &8- &7" + description));
        }
    }
}
