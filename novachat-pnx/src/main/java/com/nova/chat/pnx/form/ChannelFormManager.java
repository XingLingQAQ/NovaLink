package com.nova.chat.pnx.form;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.chat.ChatInterceptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages PowerNukkitX Form API windows for channel selection and management.
 * Provides a GUI-based experience for Bedrock players.
 * 
 * Requirements: 28.8
 */
public class ChannelFormManager {

    private final NovaChatPNX plugin;
    
    // Form IDs for tracking responses
    public static final int FORM_CHANNEL_SELECT = 1001;
    public static final int FORM_JOIN_CHANNEL = 1002;
    public static final int FORM_CREATE_CHANNEL = 1003;
    public static final int FORM_LEAVE_CONFIRM = 1004;
    public static final int FORM_CHANNEL_INFO = 1005;
    public static final int FORM_SETTINGS = 1006;
    
    // Track pending form data per player
    private final Map<UUID, String> pendingChannelJoin = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingChannelLeave = new ConcurrentHashMap<>();
    
    // Track available channels (updated from backend)
    private final Set<String> availableChannels = new HashSet<>(Arrays.asList("global", "local"));
    
    // Quick-join channel list (configurable)
    private final List<String> quickJoinChannels = new ArrayList<>(Arrays.asList("global", "local"));

    public ChannelFormManager(NovaChatPNX plugin) {
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
        UUID playerId = player.getUniqueId();
        FormWindowSimple form = new FormWindowSimple(
            I18n.tr(playerId, "chat.form.title.select"),
            ""
        );

        // Get current channel
        ChatInterceptor.PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getCurrentChannel();
        boolean chatEnabled = state.isChatEnabled();

        // Build content with current status
        StringBuilder content = new StringBuilder();
        content.append(I18n.tr(playerId, "chat.form.content.current_channel", currentChannel)).append("\n");
        content.append(I18n.tr(playerId, "chat.form.content.chat_status",
                chatEnabled ? I18n.tr(playerId, "chat.debug.value_on") : I18n.tr(playerId, "chat.debug.value_off"))).append("\n");
        content.append(I18n.tr(playerId, "chat.form.content.connection",
                (plugin.getNetworkClient() != null && plugin.getNetworkClient().isAuthenticated())
                        ? I18n.tr(playerId, "chat.debug.value_connected")
                        : I18n.tr(playerId, "chat.debug.value_disconnected"))).append("\n");
        content.append("\n").append(I18n.tr(playerId, "chat.form.content.select_action"));
        form.setContent(content.toString());

        // Add main action buttons
        form.addButton(new ElementButton(I18n.tr(playerId, "chat.form.button.join")));
        form.addButton(new ElementButton(I18n.tr(playerId, "chat.form.button.create")));
        form.addButton(new ElementButton(I18n.tr(playerId, "chat.form.button.leave")));
        form.addButton(new ElementButton(I18n.tr(playerId, "chat.form.button.toggle_chat")));
        form.addButton(new ElementButton(I18n.tr(playerId, "chat.form.button.info")));
        form.addButton(new ElementButton(I18n.tr(playerId, "chat.form.button.settings")));

        // Add quick-join buttons for common channels (excluding current)
        for (String channel : quickJoinChannels) {
            if (!channel.equals(currentChannel)) {
                form.addButton(new ElementButton(I18n.tr(playerId, "chat.form.button.quick_join", channel)));
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
        UUID playerId = player.getUniqueId();
        FormWindowCustom form = new FormWindowCustom(I18n.tr(playerId, "chat.form.title.join"));

        form.addElement(new ElementLabel(I18n.tr(playerId, "chat.form.label.join_prompt")));

        // Add dropdown for known channels if available
        if (!availableChannels.isEmpty()) {
            List<String> channelList = new ArrayList<>(availableChannels);
            channelList.add(0, I18n.tr(playerId, "chat.form.label.manual_input"));
            form.addElement(new ElementDropdown(I18n.tr(playerId, "chat.form.label.select_channel"), channelList, 0));
        }

        form.addElement(new ElementInput(I18n.tr(playerId, "chat.form.label.channel_id"),
                I18n.tr(playerId, "chat.form.placeholder.channel_id"), ""));
        form.addElement(new ElementInput(I18n.tr(playerId, "chat.form.label.password_optional"),
                I18n.tr(playerId, "chat.form.placeholder.password_join"), ""));

        player.showFormWindow(form, FORM_JOIN_CHANNEL);
    }

    /**
     * Shows the create channel form.
     *
     * @param player the player to show the form to
     */
    public void showCreateChannelForm(Player player) {
        UUID playerId = player.getUniqueId();
        FormWindowCustom form = new FormWindowCustom(I18n.tr(playerId, "chat.form.title.create"));

        form.addElement(new ElementLabel(I18n.tr(playerId, "chat.form.label.create_prompt")));
        form.addElement(new ElementInput(I18n.tr(playerId, "chat.form.label.channel_name"),
                I18n.tr(playerId, "chat.form.placeholder.channel_name"), ""));
        form.addElement(new ElementInput(I18n.tr(playerId, "chat.form.label.password_optional"),
                I18n.tr(playerId, "chat.form.placeholder.password_create"), ""));
        form.addElement(new ElementToggle(I18n.tr(playerId, "chat.form.label.auto_join"), true));

        player.showFormWindow(form, FORM_CREATE_CHANNEL);
    }

    /**
     * Shows the leave channel confirmation form.
     *
     * @param player the player to show the form to
     */
    public void showLeaveConfirmForm(Player player) {
        UUID playerId = player.getUniqueId();
        ChatInterceptor.PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getCurrentChannel();
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();

        if (currentChannel.equals(defaultChannel)) {
            sendError(player, I18n.tr(playerId, "chat.action.already_default"));
            return;
        }

        // Store pending leave for this player
        pendingChannelLeave.put(player.getUniqueId(), currentChannel);

        FormWindowModal form = new FormWindowModal(
            I18n.tr(playerId, "chat.form.title.leave_confirm"),
            I18n.tr(playerId, "chat.form.content.leave_prompt", currentChannel, defaultChannel),
            I18n.tr(playerId, "chat.form.button.confirm"),
            I18n.tr(playerId, "chat.form.button.cancel")
        );

        player.showFormWindow(form, FORM_LEAVE_CONFIRM);
    }
    
    /**
     * Shows the channel info form.
     *
     * @param player the player to show the form to
     */
    public void showChannelInfoForm(Player player) {
        UUID playerId = player.getUniqueId();
        ChatInterceptor.PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getCurrentChannel();

        FormWindowSimple form = new FormWindowSimple(
            I18n.tr(playerId, "chat.form.title.info"),
            ""
        );

        StringBuilder content = new StringBuilder();
        content.append(I18n.tr(playerId, "chat.form.content.info_current_channel", currentChannel)).append("\n\n");
        content.append(I18n.tr(playerId, "chat.form.content.info_your_status")).append("\n");
        content.append(I18n.tr(playerId, "chat.form.content.info_world", player.getLevel().getName())).append("\n");
        content.append(I18n.tr(playerId, "chat.form.content.info_chat_status",
                state.isChatEnabled() ? I18n.tr(playerId, "chat.debug.value_on") : I18n.tr(playerId, "chat.debug.value_off"))).append("\n\n");

        content.append(I18n.tr(playerId, "chat.form.content.info_available")).append("\n");
        String marker = I18n.tr(playerId, "chat.form.content.info_current_marker");
        for (String channel : availableChannels) {
            if (channel.equals(currentChannel)) {
                content.append("§a• ").append(channel).append(" ").append(marker).append("\n");
            } else {
                content.append("§7• ").append(channel).append("\n");
            }
        }

        form.setContent(content.toString());
        form.addButton(new ElementButton(I18n.tr(playerId, "chat.form.button.back")));

        player.showFormWindow(form, FORM_CHANNEL_INFO);
    }
    
    /**
     * Shows the settings form.
     *
     * @param player the player to show the form to
     */
    public void showSettingsForm(Player player) {
        UUID playerId = player.getUniqueId();
        ChatInterceptor.PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);

        FormWindowCustom form = new FormWindowCustom(I18n.tr(playerId, "chat.form.title.settings"));

        form.addElement(new ElementLabel(I18n.tr(playerId, "chat.form.label.settings_prompt")));
        form.addElement(new ElementToggle(I18n.tr(playerId, "chat.form.label.chat_enabled"), state.isChatEnabled()));
        form.addElement(new ElementLabel(I18n.tr(playerId, "chat.form.content.settings_chat")));

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
            case 3: // Toggle chat
                toggleChat(player);
                break;
            case 4: // Channel info
                showChannelInfoForm(player);
                break;
            case 5: // Settings
                showSettingsForm(player);
                break;
            default:
                // Quick join buttons (index 6+)
                ChatInterceptor.PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
                String currentChannel = state.getCurrentChannel();
                
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
            sendError(player, I18n.tr(player.getUniqueId(), "chat.action.enter_channel_id"));
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
            sendError(player, I18n.tr(player.getUniqueId(), "chat.action.enter_channel_name"));
            return;
        }
        
        createChannel(player, channelName.trim(), (password == null || password.isEmpty()) ? null : password, autoJoin);
    }
    
    /**
     * Handles the leave confirmation form response.
     */
    private void handleLeaveConfirmResponse(Player player, Object response) {
        if (response instanceof FormResponseModal) {
            FormResponseModal modalResponse = (FormResponseModal) response;
            if (modalResponse.getClickedButtonId() == 0) {
                // User confirmed
                String channelToLeave = pendingChannelLeave.remove(player.getUniqueId());
                if (channelToLeave != null) {
                    leaveChannel(player, channelToLeave);
                }
            } else {
                // User cancelled
                pendingChannelLeave.remove(player.getUniqueId());
                sendMessage(player, I18n.tr(player.getUniqueId(), "chat.action.cancelled"));
            }
        }
    }
    
    /**
     * Handles the settings form response.
     */
    private void handleSettingsResponse(Player player, FormResponseCustom response) {
        // Form has: Label, Toggle (chat enabled), Label
        boolean chatEnabled = response.getToggleResponse(1);

        ChatInterceptor.PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        state.setChatEnabled(chatEnabled);

        sendSuccess(player, I18n.tr(player.getUniqueId(), "chat.command.toggle.switched",
                chatEnabled ? I18n.tr(player.getUniqueId(), "chat.debug.value_on") : I18n.tr(player.getUniqueId(), "chat.debug.value_off")));
    }


    /**
     * Joins a channel.
     */
    private void joinChannel(Player player, String channelId, String password) {
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            sendError(player, I18n.tr(player.getUniqueId(), "chat.network.not_connected"));
            return;
        }

        ChannelActionPacket packet = new ChannelActionPacket(
            ChannelAction.JOIN,
            channelId,
            password
        );
        packet.addExtra("player_uuid", player.getUniqueId().toString());
        packet.addExtra("player_name", player.getName());

        plugin.getNetworkClient().sendPacket(packet);

        // Update local state
        plugin.getChatInterceptor().setPlayerChannel(player, channelId);

        sendSuccess(player, PlayerMessages.joining(player.getUniqueId(), channelId));
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
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            sendError(player, I18n.tr(player.getUniqueId(), "chat.network.not_connected"));
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

        sendSuccess(player, I18n.tr(player.getUniqueId(), "chat.create.progress", channelName));
    }

    /**
     * Leaves a specific channel.
     *
     * @param player the player leaving the channel
     * @param channelId the channel to leave
     */
    private void leaveChannel(Player player, String channelId) {
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();

        if (channelId.equals(defaultChannel)) {
            sendError(player, I18n.tr(player.getUniqueId(), "chat.action.already_default"));
            return;
        }

        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            sendError(player, I18n.tr(player.getUniqueId(), "chat.network.not_connected"));
            return;
        }

        ChannelActionPacket packet = new ChannelActionPacket(
            ChannelAction.LEAVE,
            channelId,
            null
        );
        packet.addExtra("player_uuid", player.getUniqueId().toString());
        packet.addExtra("player_name", player.getName());

        plugin.getNetworkClient().sendPacket(packet);

        // Update local state
        plugin.getChatInterceptor().setPlayerChannel(player, defaultChannel);

        sendSuccess(player, PlayerMessages.left(player.getUniqueId(), channelId, defaultChannel));
    }

    /**
     * Toggles the player's chat enabled state.
     */
    private void toggleChat(Player player) {
        ChatInterceptor.PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        boolean newState = !state.isChatEnabled();
        state.setChatEnabled(newState);

        sendSuccess(player, I18n.tr(player.getUniqueId(), "chat.command.toggle.switched",
                newState ? I18n.tr(player.getUniqueId(), "chat.debug.value_on") : I18n.tr(player.getUniqueId(), "chat.debug.value_off")));

        // Show main menu again after toggle
        showChannelSelectionForm(player);
    }
    
    /**
     * Gets the set of available channels.
     *
     * @return unmodifiable set of available channel IDs
     */
    public Set<String> getAvailableChannels() {
        return Collections.unmodifiableSet(availableChannels);
    }
    
    /**
     * Gets the list of quick-join channels.
     *
     * @return unmodifiable list of quick-join channel IDs
     */
    public List<String> getQuickJoinChannels() {
        return Collections.unmodifiableList(quickJoinChannels);
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

    // Helper methods for sending messages
    private void sendError(Player player, String message) {
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        String format = plugin.getNovaChatConfig().getFormatError();
        player.sendMessage(prefix + format.replace("{message}", message));
    }

    private void sendSuccess(Player player, String message) {
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        String format = plugin.getNovaChatConfig().getFormatSuccess();
        player.sendMessage(prefix + format.replace("{message}", message));
    }

    private void sendMessage(Player player, String message) {
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        player.sendMessage(prefix + message);
    }
}
