package com.nova.chat.mod.platform;

import java.util.Collections;
import java.util.List;

/**
 * Interface for handling individual commands.
 *
 * <p>Tab-completion is optional: the default {@link #tabComplete(String[])}
 * returns an empty list. Loader-specific {@code CommandRegistrar}
 * implementations (fabric / quilt / neoforge / forge) should forward their
 * Brigadier suggestion provider to this method, and concrete commands that
 * accept a channel-name argument can override it to surface the backend's
 * known channel list (via {@code KnownChannelRegistry}) once the loader
 * subprojects are enabled.
 */
public interface CommandHandler {

    /**
     * Execute the command
     * @param args the command arguments
     * @param context the command context
     * @return true if the command was executed successfully
     */
    boolean execute(String[] args, CommandContext context);

    /**
     * Get the command description
     * @return the command description
     */
    String getDescription();

    /**
     * Get the command usage
     * @return the command usage string
     */
    String getUsage();

    /**
     * Suggest completions for the current argument cursor.
     *
     * <p>Default returns no suggestions. Commands that take a channel name
     * can override this to return the backend's known channel list.
     *
     * @param args the arguments typed so far (last element is the partial token)
     * @return suggested completions; empty by default
     */
    default List<String> tabComplete(String[] args) {
        return Collections.emptyList();
    }
}
