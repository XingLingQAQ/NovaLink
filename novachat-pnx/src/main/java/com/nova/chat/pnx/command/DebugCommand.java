package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Debug sub-command - shows debug information and toggles debug mode.
 * 
 * Requirements: 29.1, 29.2
 */
public class DebugCommand extends AbstractSubCommand {

    public DebugCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public String getDescription() {
        return "显示调试信息/切换调试模式";
    }

    @Override
    public String getUsage() {
        return "/nc debug [on|off]";
    }

    @Override
    public String getPermission() {
        return "novachat.admin";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        // If argument provided, toggle debug mode
        if (args.length > 0) {
            String toggle = args[0].toLowerCase();
            if (toggle.equals("on") || toggle.equals("true")) {
                plugin.setDebugMode(true);
                sendSuccess(sender, "调试模式已开启");
            } else if (toggle.equals("off") || toggle.equals("false")) {
                plugin.setDebugMode(false);
                sendSuccess(sender, "调试模式已关闭");
            } else {
                sendError(sender, "用法: /nc debug [on|off]");
            }
            return true;
        }
        
        // Show debug information
        boolean connected = plugin.getNetworkClient() != null && plugin.getNetworkClient().isConnected();
        boolean authenticated = plugin.getNetworkClient() != null && plugin.getNetworkClient().isAuthenticated();
        
        sender.sendMessage(TextFormat.GOLD + "=== NovaChat 调试信息 ===");
        sender.sendMessage(TextFormat.YELLOW + "后端连接: " + 
            (connected ? TextFormat.GREEN + "已连接" : TextFormat.RED + "未连接"));
        sender.sendMessage(TextFormat.YELLOW + "认证状态: " + 
            (authenticated ? TextFormat.GREEN + "已认证" : TextFormat.RED + "未认证"));
        sender.sendMessage(TextFormat.YELLOW + "后端地址: " + TextFormat.WHITE + 
            plugin.getNovaChatConfig().getBackendHost() + ":" + 
            plugin.getNovaChatConfig().getBackendPort());
        sender.sendMessage(TextFormat.YELLOW + "客户端ID: " + TextFormat.WHITE + 
            plugin.getNovaChatConfig().getBackendUsername());
        sender.sendMessage(TextFormat.YELLOW + "调试模式: " + TextFormat.WHITE + 
            (plugin.isDebugMode() ? "开启" : "关闭"));
        sender.sendMessage(TextFormat.YELLOW + "在线玩家: " + TextFormat.WHITE + 
            plugin.getServer().getOnlinePlayers().size());
        sender.sendMessage(TextFormat.YELLOW + "默认频道: " + TextFormat.WHITE + 
            plugin.getNovaChatConfig().getDefaultChannel());
        sender.sendMessage(TextFormat.YELLOW + "世界路由: " + TextFormat.WHITE + 
            (plugin.getNovaChatConfig().isWorldRoutingEnabled() ? "开启" : "关闭"));

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
