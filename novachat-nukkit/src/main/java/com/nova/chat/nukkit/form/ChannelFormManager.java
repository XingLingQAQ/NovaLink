package com.nova.chat.nukkit.form;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.state.ChatMode;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Nukkit Form API windows for channel selection and management.
 * Provides a GUI-based experience for Bedrock players.
 * 
 * This class implements the Form API integration for Nukkit/Bedrock servers,
 * allowing players to interact with the channel system through a graphical
 * user interface instead of text commands.
 * 
 * Features:
 * - Main channel selection menu
 * - Join channel form with password support
 * - Create private channel form
 * - Leave channel confirmation
 * - Chat mode toggle
 * - Channel info display
 * - Quick-join buttons for common channels
 * 
 * Requirements: 23.4
 */
public class ChannelFormManager {

    private final NovaChatNukkit plugin;
    
    // Form IDs for tracking responses
    private static final int FORM_CHANNEL_SELECT = 1001;
    private static final int FORM_JOIN_CHANNEL = 1002;
    private static final int FORM_CREATE_CHANNEL = 1003;
    private static final int FORM_LEAVE_CONFIRM = 1004;
    private static final int FORM_CHANNEL_INFO = 1005;
    private static final int FORM_SETTINGS = 1006;
    
    // Track pending form data per player
    private final Map<UUID, String> pendingChannelJoin = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingChannelLeave = new ConcurrentHashMap<>();
    
    // Track available channels (updated from backend)
    private final Set<String> availableChannels = new HashSet<>(Arrays.asList("global", "local"));
    
    // Quick-join channel list (configurable)
    private final List<String> quickJoinChannels = new ArrayList<>(Arrays.asList("global", "local"));

    public ChannelFormManager(NovaChatNukkit plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Updates the list of available channels.
     * Called when receiving channel list from backend.
     *
     * @param channels the list of available channel IDs
     */
    public void updateAvailableChannels(List<String> channels) {
        availableChannels.clear();
        availableChannels.addAll(channels);
        plugin.debug("Updated available channels: " + channels);
    }
    
    /**
     * Adds a channel to the quick-join list.
     *
     * @param channelId the channel ID to add
     */
    public void addQuickJoinChannel(String channelId) {
        if (!quickJoinChannels.contains(channelId)) {
            quickJoinChannels.add(channelId);
        }
    }

    /**
     * Shows the main channel selection form to a player.
     *
     * @param player the player to show the form to
     */
    public void showChannelSelectionForm(Player player) {
        FormWindowSimple form = new FormWindowSimple(
            "§b频道选择 / Channel Selection",
            ""
        );
        
        // Get current channel and chat mode
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getActiveChannel();
        ChatMode currentMode = state.isModeOverridden() ? state.getChatMode() : plugin.getChatInterceptor().getGlobalMode();
        String modeText = currentMode == ChatMode.REPLACE ? "频道模式" : "混合模式";
        
        // Build content with current status
        StringBuilder content = new StringBuilder();
        content.append("§7当前频道 / Current Channel: §e").append(currentChannel).append("\n");
        content.append("§7聊天模式 / Chat Mode: §e").append(modeText).append("\n");
        content.append("§7连接状态 / Connection: ");
        if (plugin.getNetworkClient().isAuthenticated()) {
            content.append("§a已连接 / Connected\n");
        } else {
            content.append("§c未连接 / Disconnected\n");
        }
        content.append("\n§7选择一个操作 / Select an action:");
        form.setContent(content.toString());
        
        // Add main action buttons
        form.addButton(new ElementButton("§a加入频道\n§7Join Channel"));
        form.addButton(new ElementButton("§e创建私有频道\n§7Create Private Channel"));
        form.addButton(new ElementButton("§c离开当前频道\n§7Leave Current Channel"));
        form.addButton(new ElementButton("§d切换聊天模式\n§7Toggle Chat Mode"));
        form.addButton(new ElementButton("§b频道信息\n§7Channel Info"));
        form.addButton(new ElementButton("§7设置\n§7Settings"));
        
        // Add quick-join buttons for common channels (excluding current)
        for (String channel : quickJoinChannels) {
            if (!channel.equals(currentChannel)) {
                form.addButton(new ElementButton("§b快速加入: " + channel + "\n§7Quick Join"));
            }
        }
        
        player.showFormWindow(form, FORM_CHANNEL_SELECT);
    }

    /**
     * Shows the join channel form with password input.
     *
     * @param player the player to show the form to
     */
    public void showJoinChannelForm(Player player) {
        FormWindowCustom form = new FormWindowCustom("§a加入频道 / Join Channel");
        
        form.addElement(new ElementLabel("§7输入要加入的频道ID和密码（如果需要）\n§7Enter channel ID and password (if required)"));
        
        // Add dropdown for known channels if available
        if (!availableChannels.isEmpty()) {
            List<String> channelList = new ArrayList<>(availableChannels);
            channelList.add(0, "-- 手动输入 / Manual Input --");
            form.addElement(new ElementDropdown("选择频道 / Select Channel", channelList, 0));
        }
        
        form.addElement(new ElementInput("频道ID / Channel ID", "例如: NC-5A3F / e.g. NC-5A3F", ""));
        form.addElement(new ElementInput("密码（可选）/ Password (Optional)", "如果频道需要密码 / If channel requires password", ""));
        
        player.showFormWindow(form, FORM_JOIN_CHANNEL);
    }

    /**
     * Shows the create channel form.
     *
     * @param player the player to show the form to
     */
    public void showCreateChannelForm(Player player) {
        FormWindowCustom form = new FormWindowCustom("§e创建私有频道 / Create Private Channel");
        
        form.addElement(new ElementLabel("§7创建一个新的私有频道\n§7Create a new private channel"));
        form.addElement(new ElementInput("频道名称 / Channel Name", "给你的频道起个名字 / Name your channel", ""));
        form.addElement(new ElementInput("密码（可选）/ Password (Optional)", "留空将自动生成 / Leave empty to auto-generate", ""));
        form.addElement(new ElementToggle("自动加入 / Auto Join", true));
        
        player.showFormWindow(form, FORM_CREATE_CHANNEL);
    }
    
    /**
     * Shows the leave channel confirmation form.
     *
     * @param player the player to show the form to
     */
    public void showLeaveConfirmForm(Player player) {
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getActiveChannel();
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();
        
        if (currentChannel.equals(defaultChannel)) {
            plugin.getMessageHelper().sendError(player, "你已经在默认频道中 / You are already in the default channel");
            return;
        }
        
        // Store pending leave for this player
        pendingChannelLeave.put(player.getUniqueId(), currentChannel);
        
        FormWindowModal form = new FormWindowModal(
            "§c确认离开 / Confirm Leave",
            "§7你确定要离开频道 §e" + currentChannel + " §7吗？\n" +
            "§7Are you sure you want to leave channel §e" + currentChannel + "§7?\n\n" +
            "§7你将被移动到默认频道: §e" + defaultChannel + "\n" +
            "§7You will be moved to default channel: §e" + defaultChannel,
            "§a确认 / Confirm",
            "§c取消 / Cancel"
        );
        
        player.showFormWindow(form, FORM_LEAVE_CONFIRM);
    }
    
    /**
     * Shows the channel info form.
     *
     * @param player the player to show the form to
     */
    public void showChannelInfoForm(Player player) {
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getActiveChannel();
        
        FormWindowSimple form = new FormWindowSimple(
            "§b频道信息 / Channel Info",
            ""
        );
        
        StringBuilder content = new StringBuilder();
        content.append("§e当前频道 / Current Channel:\n");
        content.append("§7ID: §f").append(currentChannel).append("\n\n");
        content.append("§e你的状态 / Your Status:\n");
        content.append("§7世界 / World: §f").append(player.getLevel().getName()).append("\n");
        
        ChatMode mode = state.isModeOverridden() ? state.getChatMode() : plugin.getChatInterceptor().getGlobalMode();
        content.append("§7聊天模式 / Chat Mode: §f").append(mode == ChatMode.REPLACE ? "频道模式 / Channel Mode" : "混合模式 / Hybrid Mode").append("\n\n");
        
        content.append("§e可用频道 / Available Channels:\n");
        for (String channel : availableChannels) {
            if (channel.equals(currentChannel)) {
                content.append("§a• ").append(channel).append(" §7(当前 / current)\n");
            } else {
                content.append("§7• ").append(channel).append("\n");
            }
        }
        
        form.setContent(content.toString());
        form.addButton(new ElementButton("§a返回 / Back"));
        
        player.showFormWindow(form, FORM_CHANNEL_INFO);
    }
    
    /**
     * Shows the settings form.
     *
     * @param player the player to show the form to
     */
    public void showSettingsForm(Player player) {
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChatMode currentMode = state.isModeOverridden() ? state.getChatMode() : plugin.getChatInterceptor().getGlobalMode();
        
        FormWindowCustom form = new FormWindowCustom("§7设置 / Settings");
        
        form.addElement(new ElementLabel("§7调整你的聊天设置\n§7Adjust your chat settings"));
        form.addElement(new ElementToggle("频道模式 / Channel Mode", currentMode == ChatMode.REPLACE));
        form.addElement(new ElementLabel("§7频道模式: 所有聊天发送到当前频道\n§7Channel Mode: All chat goes to current channel\n\n§7混合模式: 原版聊天正常工作\n§7Hybrid Mode: Vanilla chat works normally"));
        
        player.showFormWindow(form, FORM_SETTINGS);
    }

    /**
     * Handles form responses from players.
     *
     * @param player the player who submitted the form
     * @param formId the form ID
     * @param response the form response (can be null if closed)
     */
    public void handleFormResponse(Player player, int formId, Object response) {
        if (response == null) {
            // Form was closed without response - clean up pending data
            pendingChannelJoin.remove(player.getUniqueId());
            pendingChannelLeave.remove(player.getUniqueId());
            return;
        }
        
        switch (formId) {
            case FORM_CHANNEL_SELECT:
                handleChannelSelectResponse(player, (FormResponseSimple) response);
                break;
            case FORM_JOIN_CHANNEL:
                handleJoinChannelResponse(player, (FormResponseCustom) response);
                break;
            case FORM_CREATE_CHANNEL:
                handleCreateChannelResponse(player, (FormResponseCustom) response);
                break;
            case FORM_LEAVE_CONFIRM:
                handleLeaveConfirmResponse(player, response);
                break;
            case FORM_CHANNEL_INFO:
                // Info form just has a back button, show main menu
                showChannelSelectionForm(player);
                break;
            case FORM_SETTINGS:
                handleSettingsResponse(player, (FormResponseCustom) response);
                break;
        }
    }

    /**
     * Handles the channel selection form response.
     */
    private void handleChannelSelectResponse(Player player, FormResponseSimple response) {
        int buttonId = response.getClickedButtonId();
        
        switch (buttonId) {
            case 0: // Join channel
                showJoinChannelForm(player);
                break;
            case 1: // Create channel
                showCreateChannelForm(player);
                break;
            case 2: // Leave channel
                showLeaveConfirmForm(player);
                break;
            case 3: // Toggle mode
                toggleChatMode(player);
                break;
            case 4: // Channel info
                showChannelInfoForm(player);
                break;
            case 5: // Settings
                showSettingsForm(player);
                break;
            default:
                // Quick join buttons (index 6+)
                PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
                String currentChannel = state.getActiveChannel();
                
                // Build list of quick-join channels excluding current
                List<String> availableQuickJoin = new ArrayList<>();
                for (String channel : quickJoinChannels) {
                    if (!channel.equals(currentChannel)) {
                        availableQuickJoin.add(channel);
                    }
                }
                
                int channelIndex = buttonId - 6;
                if (channelIndex >= 0 && channelIndex < availableQuickJoin.size()) {
                    joinChannel(player, availableQuickJoin.get(channelIndex), null);
                }
                break;
        }
    }

    /**
     * Handles the join channel form response.
     */
    private void handleJoinChannelResponse(Player player, FormResponseCustom response) {
        String channelId = null;
        String password = null;
        
        // Check if we have a dropdown (available channels list)
        if (!availableChannels.isEmpty()) {
            // Form has: Label, Dropdown, Input (channel ID), Input (password)
            int dropdownSelection = response.getDropdownResponse(1).getElementID();
            
            if (dropdownSelection > 0) {
                // User selected from dropdown (index 0 is "manual input")
                List<String> channelList = new ArrayList<>(availableChannels);
                channelId = channelList.get(dropdownSelection - 1);
            } else {
                // User wants to input manually
                channelId = response.getInputResponse(2);
            }
            password = response.getInputResponse(3);
        } else {
            // Form has: Label, Input (channel ID), Input (password)
            channelId = response.getInputResponse(1);
            password = response.getInputResponse(2);
        }
        
        if (channelId == null || channelId.trim().isEmpty()) {
            plugin.getMessageHelper().sendError(player, "请输入频道ID / Please enter a channel ID");
            return;
        }
        
        joinChannel(player, channelId.trim(), (password == null || password.isEmpty()) ? null : password);
    }

    /**
     * Handles the create channel form response.
     */
    private void handleCreateChannelResponse(Player player, FormResponseCustom response) {
        // Form has: Label, Input (name), Input (password), Toggle (auto-join)
        String channelName = response.getInputResponse(1);
        String password = response.getInputResponse(2);
        boolean autoJoin = response.getToggleResponse(3);
        
        if (channelName == null || channelName.trim().isEmpty()) {
            plugin.getMessageHelper().sendError(player, "请输入频道名称 / Please enter a channel name");
            return;
        }
        
        createChannel(player, channelName.trim(), (password == null || password.isEmpty()) ? null : password, autoJoin);
    }
    
    /**
     * Handles the leave confirmation form response.
     */
    private void handleLeaveConfirmResponse(Player player, Object response) {
        // Modal form response is a boolean (true = first button, false = second button)
        if (response instanceof cn.nukkit.form.response.FormResponseModal) {
            cn.nukkit.form.response.FormResponseModal modalResponse = (cn.nukkit.form.response.FormResponseModal) response;
            if (modalResponse.getClickedButtonId() == 0) {
                // User confirmed
                String channelToLeave = pendingChannelLeave.remove(player.getUniqueId());
                if (channelToLeave != null) {
                    leaveChannel(player, channelToLeave);
                }
            } else {
                // User cancelled
                pendingChannelLeave.remove(player.getUniqueId());
                plugin.getMessageHelper().sendMessage(player, "已取消 / Cancelled");
            }
        }
    }
    
    /**
     * Handles the settings form response.
     */
    private void handleSettingsResponse(Player player, FormResponseCustom response) {
        // Form has: Label, Toggle (channel mode), Label
        boolean channelMode = response.getToggleResponse(1);
        
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChatMode newMode = channelMode ? ChatMode.REPLACE : ChatMode.HYBRID;
        state.setChatMode(newMode);
        state.setModeOverridden(true);
        
        String modeDescription = newMode == ChatMode.REPLACE 
            ? "频道模式 / Channel Mode" 
            : "混合模式 / Hybrid Mode";
        
        plugin.getMessageHelper().sendSuccess(player, "聊天模式已设置为: §e" + modeDescription);
    }

    /**
     * Joins a channel via {@link ChannelCommandService}.
     * Preserves Chinese form UX messages.
     */
    private void joinChannel(Player player, String channelId, String password) {
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.join(state, channelId, password, player.getName(), player.getLevel().getName());

        if (result.isSuccess()) {
            plugin.getMessageHelper().sendSuccess(player, "正在加入频道 " + channelId + "...");
            plugin.debug("Player " + player.getName() + " joined channel via form: " + channelId);
        } else {
            plugin.getMessageHelper().sendError(player, "未连接到聊天服务器");
            plugin.debug("Player " + player.getName() + " failed form join " + channelId
                    + ": " + result.getMessage());
        }
    }

    /**
     * Creates a new private channel.
     */
    private void createChannel(Player player, String channelName, String password) {
        createChannel(player, channelName, password, true);
    }
    
    /**
     * Creates a new private channel with auto-join option.
     *
     * @param player the player creating the channel
     * @param channelName the name for the new channel
     * @param password optional password (null for auto-generated)
     * @param autoJoin whether to automatically join the channel after creation
     */
    private void createChannel(Player player, String channelName, String password, boolean autoJoin) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            plugin.getMessageHelper().sendError(player, "未连接到聊天服务器 / Not connected to chat server");
            return;
        }
        
        ChannelActionPacket packet = new ChannelActionPacket(
            ChannelAction.CREATE,
            null, // ID will be generated by backend
            password
        );
        packet.addExtra("player_uuid", player.getUniqueId().toString());
        packet.addExtra("player_name", player.getName());
        packet.addExtra("channel_name", channelName);
        packet.addExtra("auto_join", String.valueOf(autoJoin));
        
        plugin.getNetworkClient().sendPacket(packet);
        
        plugin.getMessageHelper().sendSuccess(player, "正在创建频道 / Creating channel: " + channelName + "...");
    }

    /**
     * Leaves a specific channel via {@link ChannelCommandService}.
     * After success, restores the configured default channel (Nukkit leave UX).
     *
     * @param player the player leaving the channel
     * @param channelId the channel to leave
     */
    private void leaveChannel(Player player, String channelId) {
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();

        if (channelId.equals(defaultChannel)) {
            plugin.getMessageHelper().sendError(player, "你已经在默认频道中 / You are already in the default channel");
            return;
        }

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.leave(state, channelId, player.getName());

        if (result.isSuccess()) {
            if (!defaultChannel.equals(state.getActiveChannel())) {
                state.setActiveChannel(defaultChannel);
            }
            plugin.getMessageHelper().sendSuccess(player, "已离开频道 / Left channel: " + channelId);
            plugin.debug("Player " + player.getName() + " left channel via form: " + channelId);
        } else if (result.getMessage() != null && result.getMessage().contains("Not in a channel")) {
            plugin.getMessageHelper().sendError(player, "你当前不在任何频道中");
        } else {
            plugin.getMessageHelper().sendError(player, "未连接到聊天服务器 / Not connected to chat server");
            plugin.debug("Player " + player.getName() + " failed form leave " + channelId
                    + ": " + result.getMessage());
        }
    }

    /**
     * Toggles the player's chat mode via {@link ChannelCommandService#toggle}.
     */
    private void toggleChatMode(Player player) {
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.toggle(state);

        if (!result.isSuccess()) {
            plugin.getMessageHelper().sendError(player, result.getMessage());
            return;
        }

        ChatMode newMode = state.getChatMode();
        String modeDescription = newMode == ChatMode.REPLACE
            ? "频道模式 / Channel Mode (所有聊天发送到频道 / All chat goes to channel)"
            : "混合模式 / Hybrid Mode (原版聊天正常工作 / Vanilla chat works normally)";

        plugin.getMessageHelper().sendSuccess(player, "聊天模式已切换为 / Chat mode changed to: §e" + modeDescription);

        // Show main menu again after toggle
        showChannelSelectionForm(player);
    }
    
    /**
     * Gets the set of available channels.
     *
     * @return unmodifiable set of available channel IDs
     */
    public Set<String> getAvailableChannels() {
        return java.util.Collections.unmodifiableSet(availableChannels);
    }
    
    /**
     * Gets the list of quick-join channels.
     *
     * @return unmodifiable list of quick-join channel IDs
     */
    public List<String> getQuickJoinChannels() {
        return java.util.Collections.unmodifiableList(quickJoinChannels);
    }
    
    /**
     * Clears all pending form data for a player.
     * Should be called when a player disconnects.
     *
     * @param playerId the player's UUID
     */
    public void clearPendingData(UUID playerId) {
        pendingChannelJoin.remove(playerId);
        pendingChannelLeave.remove(playerId);
    }
}
