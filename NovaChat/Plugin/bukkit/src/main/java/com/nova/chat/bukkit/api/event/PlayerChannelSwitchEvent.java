package com.nova.chat.bukkit.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Event fired when a player switches channels.
 * Other plugins can listen to this event to track channel changes or cancel them.
 * 
 * Requirements: 25.2 - PlayerChannelSwitchEvent for other plugins to listen
 */
public class PlayerChannelSwitchEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String oldChannel;
    private String newChannel;
    private boolean cancelled = false;

    /**
     * Creates a new PlayerChannelSwitchEvent.
     *
     * @param player the player switching channels
     * @param oldChannel the channel the player is leaving (can be null)
     * @param newChannel the channel the player is joining
     */
    public PlayerChannelSwitchEvent(Player player, String oldChannel, String newChannel) {
        super(player);
        this.oldChannel = oldChannel;
        this.newChannel = newChannel;
    }

    /**
     * Gets the channel the player is leaving.
     *
     * @return the old channel ID, or null if the player wasn't in a channel
     */
    public String getOldChannel() {
        return oldChannel;
    }

    /**
     * Gets the channel the player is joining.
     *
     * @return the new channel ID
     */
    public String getNewChannel() {
        return newChannel;
    }

    /**
     * Sets the channel the player will join.
     * This allows plugins to redirect the player to a different channel.
     *
     * @param newChannel the new channel ID
     */
    public void setNewChannel(String newChannel) {
        this.newChannel = newChannel;
    }

    /**
     * Checks if the player is joining a channel for the first time.
     *
     * @return true if the player wasn't in any channel before
     */
    public boolean isFirstJoin() {
        return oldChannel == null;
    }

    /**
     * Checks if the player is actually changing channels.
     *
     * @return true if the old and new channels are different
     */
    public boolean isChannelChange() {
        if (oldChannel == null) {
            return newChannel != null;
        }
        return !oldChannel.equals(newChannel);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
