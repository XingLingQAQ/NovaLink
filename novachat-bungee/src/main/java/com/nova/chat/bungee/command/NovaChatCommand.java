package com.nova.chat.bungee.command;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.chat.ChatListener;
import com.nova.chat.bungee.chat.ChatMode;
import com.nova.chat.bungee.chat.MessageFormatter;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main command handler for NovaChat BungeeCord plugin.
 * Handles /novachat and /nc commands.
 * 
 * Requirements: 26.1-26.4
 */
public class NovaChatCommand extends Command implements TabExecutor {
    
    private final NovaChatBungee plugin;
    private final MessageFormatter messageFormatter;
    
    /** Available subcommands */
    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "help", "join", "leave", "toggle", "reload"
    );
    
    /**
     * Creates a new NovaChatCommand.
     *
     * @param plugin the plugin instance
     */
    public NovaChatCommand(NovaChatBungee plugin) {
        super("novachat", "novachat.use", "nc");
        this.plugin = plugin;
        this.messageFormatter = plugin.getChatListener().getMessageFormatter();
    }
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return;
        }
        
        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        
        switch (subCommand) {
            case "help":
                showHelp(sender);
                break;
            case "join":
                handleJoin(sender, subArgs);
                break;
            case "leave":
                handleLeave(sender, subArgs);
                break;
            case "toggle":
                handleToggle(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            default:
                // Try to send message to channel
                handleChannelMessage(sender, subCommand, subArgs);
                break;
        }
    }
    
    /**
     * Shows help information.
     */
    private void showHelp(CommandSender sender) {
        sender.sendMessage(new TextComponent(ChatColor.GOLD + "=== NovaChat 帮助 ==="));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc help - 显示帮助信息"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc join <频道> [密码] - 加入频道"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc leave [频道] - 离开频道"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc toggle - 切换聊天模式"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc <频道> <消息> - 发送消息到指定频道"));
        
        if (sender.hasPermission("novachat.admin")) {
            sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc reload - 重载配置"));
        }
    }
    
    /**
     * Handles the join subcommand.
     */
    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }
        
        if (args.length < 1) {
            player.sendMessage(messageFormatter.formatError("用法: /nc join <频道> [密码]"));
            return;
        }
        
        String channelId = args[0];
        String password = args.length > 1 ? args[1] : "";
        
        // Update player's active channel
        ChatListener chatListener = plugin.getChatListener();
        chatListener.setPlayerChannel(player, channelId);
        
        player.sendMessage(messageFormatter.formatSuccess("已加入频道: " + channelId));
        plugin.debug("Player " + player.getName() + " joined channel: " + channelId);
    }
    
    /**
     * Handles the leave subcommand.
     */
    private void handleLeave(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }
        
        ChatListener chatListener = plugin.getChatListener();
        String currentChannel = chatListener.getPlayerChannel(player);
        
        // Reset to default channel
        String defaultChannel = plugin.getPluginConfig().getDefaultChannel();
        chatListener.setPlayerChannel(player, defaultChannel);
        
        player.sendMessage(messageFormatter.formatSuccess("已离开频道: " + currentChannel + "，已切换到默认频道: " + defaultChannel));
        plugin.debug("Player " + player.getName() + " left channel: " + currentChannel);
    }
    
    /**
     * Handles the toggle subcommand.
     */
    private void handleToggle(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }
        
        ChatListener chatListener = plugin.getChatListener();
        ChatMode newMode = chatListener.togglePlayerMode(player);
        
        String modeText = newMode == ChatMode.REPLACE ? "频道模式" : "混合模式";
        player.sendMessage(messageFormatter.formatSuccess("聊天模式已切换为: " + modeText));
        plugin.debug("Player " + player.getName() + " toggled chat mode to: " + newMode);
    }
    
    /**
     * Handles the reload subcommand.
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("novachat.admin")) {
            sender.sendMessage(messageFormatter.formatError("你没有权限执行此命令"));
            return;
        }
        
        plugin.reload();
        sender.sendMessage(messageFormatter.formatSuccess("配置已重载"));
    }
    
    /**
     * Handles sending a message to a specific channel.
     */
    private void handleChannelMessage(CommandSender sender, String channelId, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }
        
        if (args.length < 1) {
            player.sendMessage(messageFormatter.formatError("用法: /nc <频道> <消息>"));
            return;
        }
        
        String message = String.join(" ", args);
        
        ChatListener chatListener = plugin.getChatListener();
        chatListener.sendToChannel(player, channelId, message);
        
        plugin.debug("Player " + player.getName() + " sent message to channel " + channelId + ": " + message);
    }
    
    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                .filter(cmd -> cmd.startsWith(prefix))
                .collect(Collectors.toList());
        }
        
        return new ArrayList<>();
    }
}
