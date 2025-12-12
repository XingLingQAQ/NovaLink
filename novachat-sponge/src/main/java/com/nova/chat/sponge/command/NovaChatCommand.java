package com.nova.chat.sponge.command;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.sponge.chat.ChatMode;
import com.nova.chat.sponge.chat.PlayerChatState;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandExecutor;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.exception.CommandException;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.service.permission.Subject;

import java.util.Optional;

/**
 * Main command handler for NovaChat Sponge plugin.
 * Implements Sponge Command API with subcommands.
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
        // Parameters
        Parameter.Value<String> channelParam = Parameter.string().key("channel").build();
        Parameter.Value<String> passwordParam = Parameter.string().key("password").optional().build();

        return Command.builder()
            .permission("novachat.use")
            .addChild(buildHelpCommand(), "help", "?")
            .addChild(buildJoinCommand(channelParam, passwordParam), "join", "j")
            .addChild(buildLeaveCommand(), "leave", "l")
            .addChild(buildToggleCommand(), "toggle", "t")
            .addChild(buildReloadCommand(), "reload")
            .addChild(buildDebugCommand(), "debug")
            .executor(this::executeHelp)
            .build();
    }

    /**
     * Builds the help subcommand.
     */
    private Command.Parameterized buildHelpCommand() {
        return Command.builder()
            .permission("novachat.help")
            .shortDescription(Component.text("显示可用命令列表"))
            .executor(this::executeHelp)
            .build();
    }

    /**
     * Builds the join subcommand.
     */
    private Command.Parameterized buildJoinCommand(Parameter.Value<String> channelParam, 
                                                    Parameter.Value<String> passwordParam) {
        return Command.builder()
            .permission("novachat.join")
            .shortDescription(Component.text("加入一个频道"))
            .addParameter(channelParam)
            .addParameter(passwordParam)
            .executor(ctx -> executeJoin(ctx, channelParam, passwordParam))
            .build();
    }

    /**
     * Builds the leave subcommand.
     */
    private Command.Parameterized buildLeaveCommand() {
        return Command.builder()
            .permission("novachat.leave")
            .shortDescription(Component.text("离开当前频道"))
            .executor(this::executeLeave)
            .build();
    }

    /**
     * Builds the toggle subcommand.
     */
    private Command.Parameterized buildToggleCommand() {
        return Command.builder()
            .permission("novachat.toggle")
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
    private CommandResult executeHelp(CommandContext ctx) throws CommandException {
        Subject subject = ctx.subject();
        
        sendHeader(subject, "NovaChat 帮助");
        
        if (hasPermission(subject, "novachat.help")) {
            sendCommandHelp(subject, "/nc help", "显示可用命令列表");
        }
        if (hasPermission(subject, "novachat.join")) {
            sendCommandHelp(subject, "/nc join <频道ID> [密码]", "加入一个频道");
        }
        if (hasPermission(subject, "novachat.leave")) {
            sendCommandHelp(subject, "/nc leave", "离开当前频道");
        }
        if (hasPermission(subject, "novachat.toggle")) {
            sendCommandHelp(subject, "/nc toggle", "切换聊天模式");
        }
        if (hasPermission(subject, "novachat.admin.reload")) {
            sendCommandHelp(subject, "/nc reload", "重新加载配置");
        }
        if (hasPermission(subject, "novachat.admin.debug")) {
            sendCommandHelp(subject, "/nc debug", "切换调试模式");
        }
        
        sendFooter(subject);
        return CommandResult.success();
    }

    /**
     * Executes the join command.
     */
    private CommandResult executeJoin(CommandContext ctx, Parameter.Value<String> channelParam,
                                      Parameter.Value<String> passwordParam) throws CommandException {
        if (!(ctx.cause().root() instanceof ServerPlayer)) {
            sendError(ctx.subject(), "此命令只能由玩家执行");
            return CommandResult.error(Component.text("此命令只能由玩家执行"));
        }

        ServerPlayer player = (ServerPlayer) ctx.cause().root();
        
        if (!checkConnection(ctx.subject())) {
            return CommandResult.success();
        }

        String channelId = ctx.requireOne(channelParam);
        String password = ctx.one(passwordParam).orElse("");

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, channelId, password);
        packet.addExtra("playerId", player.uniqueId().toString());
        packet.addExtra("playerName", player.name());
        packet.addExtra("world", player.world().key().value());

        if (sendPacket(packet)) {
            sendMessage(ctx.subject(), "正在加入频道 &e" + channelId + "&7...");
        } else {
            sendError(ctx.subject(), "发送请求失败");
        }

        return CommandResult.success();
    }

    /**
     * Executes the leave command.
     */
    private CommandResult executeLeave(CommandContext ctx) throws CommandException {
        if (!(ctx.cause().root() instanceof ServerPlayer)) {
            sendError(ctx.subject(), "此命令只能由玩家执行");
            return CommandResult.error(Component.text("此命令只能由玩家执行"));
        }

        ServerPlayer player = (ServerPlayer) ctx.cause().root();
        
        if (!checkConnection(ctx.subject())) {
            return CommandResult.success();
        }

        PlayerChatState state = plugin.getChatListener().getState(player.uniqueId());
        if (state == null) {
            sendError(ctx.subject(), "你当前没有加入任何频道");
            return CommandResult.success();
        }

        String channelId = state.getActiveChannel();
        
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, channelId, "");
        packet.addExtra("playerId", player.uniqueId().toString());
        packet.addExtra("playerName", player.name());

        if (sendPacket(packet)) {
            sendMessage(ctx.subject(), "正在离开频道 &e" + channelId + "&7...");
        } else {
            sendError(ctx.subject(), "发送请求失败");
        }

        return CommandResult.success();
    }

    /**
     * Executes the toggle command.
     */
    private CommandResult executeToggle(CommandContext ctx) throws CommandException {
        if (!(ctx.cause().root() instanceof ServerPlayer)) {
            sendError(ctx.subject(), "此命令只能由玩家执行");
            return CommandResult.error(Component.text("此命令只能由玩家执行"));
        }

        ServerPlayer player = (ServerPlayer) ctx.cause().root();
        ChatMode newMode = plugin.getChatListener().togglePlayerMode(player);
        
        String modeText = newMode == ChatMode.REPLACE ? "&c替换模式" : "&a混合模式";
        sendSuccess(ctx.subject(), "聊天模式已切换为 " + modeText);
        
        if (newMode == ChatMode.REPLACE) {
            sendMessage(ctx.subject(), "所有聊天消息将发送到当前频道");
        } else {
            sendMessage(ctx.subject(), "原版聊天已启用，使用 /nc 命令发送频道消息");
        }

        return CommandResult.success();
    }

    /**
     * Executes the reload command.
     */
    private CommandResult executeReload(CommandContext ctx) throws CommandException {
        plugin.reload();
        sendSuccess(ctx.subject(), "配置已重新加载");
        return CommandResult.success();
    }

    /**
     * Executes the debug command.
     */
    private CommandResult executeDebug(CommandContext ctx) throws CommandException {
        boolean newState = !plugin.isDebugMode();
        plugin.setDebugMode(newState);
        
        if (newState) {
            sendSuccess(ctx.subject(), "调试模式已 &a启用");
        } else {
            sendSuccess(ctx.subject(), "调试模式已 &c禁用");
        }
        
        return CommandResult.success();
    }

    // Helper methods

    private boolean checkConnection(Subject subject) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            sendError(subject, "未连接到聊天服务器 (NC-503)");
            return false;
        }
        return true;
    }

    private boolean sendPacket(com.nova.chat.common.protocol.Packet packet) {
        if (plugin.getNetworkClient().isAuthenticated()) {
            plugin.getNetworkClient().sendPacket(packet);
            return true;
        }
        return false;
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
