package com.nova.chat.common.extension;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry for extension commands.
 *
 * <p>The registry tracks commands (including aliases) and supports best-effort
 * execution and tab completion with permission checks.
 *
 * <p>This module is platform-agnostic; platform adapters (Bukkit/Velocity/etc)
 * can optionally provide a {@link CommandExecutor} for native command registration.
 */
public class ExtensionCommandRegistry {

    private static final Logger LOGGER = Logger.getLogger(ExtensionCommandRegistry.class.getName());

    private final Map<String, RegisteredCommand> commands = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> extensionCommandNames = new ConcurrentHashMap<>();

    private volatile CommandExecutor executor;

    /**
     * Internal wrapper for a registered command.
     */
    private static final class RegisteredCommand {
        private final String extensionId;
        private final ExtensionCommand command;

        private RegisteredCommand(String extensionId, ExtensionCommand command) {
            this.extensionId = extensionId;
            this.command = command;
        }
    }

    /**
     * Interface for platform-specific command registration (Bukkit/Velocity/etc).
     */
    @FunctionalInterface
    public interface CommandExecutor {
        void registerCommand(ExtensionCommand command);
    }

    /**
     * Sets the platform-specific executor. Optional.
     */
    public void setExecutor(CommandExecutor executor) {
        this.executor = executor;
    }

    /**
     * Registers a command (and its aliases) for an extension.
     *
     * @return true if registered, false on invalid input or name conflict
     */
    public boolean register(String extensionId, ExtensionCommand command) {
        if (extensionId == null || extensionId.isBlank() || command == null) {
            return false;
        }

        Set<String> names = collectNames(command);
        if (names.isEmpty()) {
            return false;
        }

        // Fail fast on conflicts
        for (String name : names) {
            if (commands.containsKey(name)) {
                LOGGER.warning("Command name conflict: '" + name + "' already registered; extension=" + extensionId);
                return false;
            }
        }

        // Register all names
        RegisteredCommand registered = new RegisteredCommand(extensionId, command);
        for (String name : names) {
            commands.put(name, registered);
            extensionCommandNames.computeIfAbsent(extensionId, k -> ConcurrentHashMap.newKeySet()).add(name);
        }

        // Best-effort platform registration (usually only cares about primary name)
        CommandExecutor local = this.executor;
        if (local != null) {
            try {
                local.registerCommand(command);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to register command with platform: " + command.getName(), e);
            }
        }

        return true;
    }

    /**
     * Unregisters all commands for an extension (including aliases).
     */
    public void unregisterAll(String extensionId) {
        if (extensionId == null || extensionId.isBlank()) {
            return;
        }

        Set<String> names = extensionCommandNames.remove(extensionId);
        if (names == null || names.isEmpty()) {
            return;
        }

        for (String name : names) {
            RegisteredCommand rc = commands.get(name);
            if (rc != null && extensionId.equals(rc.extensionId)) {
                commands.remove(name);
            }
        }
    }

    /**
     * Gets a command by name (primary or alias).
     */
    public ExtensionCommand getCommand(String name) {
        String key = normalize(name);
        if (key == null) {
            return null;
        }
        RegisteredCommand rc = commands.get(key);
        return rc != null ? rc.command : null;
    }

    /**
     * Executes a command with permission check.
     *
     * @return true if executed successfully; false if not found or permission denied or exception thrown
     */
    public boolean execute(String name, CommandContext context) {
        ExtensionCommand command = getCommand(name);
        if (command == null || context == null) {
            return false;
        }

        String permission = command.getPermission();
        if (permission != null && !permission.isBlank() && !context.hasPermission(permission)) {
            context.sendError("You don't have permission to use this command.");
            return false;
        }

        try {
            return command.execute(context);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error executing command '" + name + "': " + e.getMessage(), e);
            context.sendError("An error occurred while executing the command.");
            return false;
        }
    }

    /**
     * Tab completion with permission check.
     */
    public List<String> tabComplete(String name, CommandContext context, String[] args) {
        ExtensionCommand command = getCommand(name);
        if (command == null || context == null) {
            return Collections.emptyList();
        }

        String permission = command.getPermission();
        if (permission != null && !permission.isBlank() && !context.hasPermission(permission)) {
            return Collections.emptyList();
        }

        try {
            return command.tabComplete(context, args != null ? args : new String[0]);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error in tab completion for '" + name + "': " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Returns all registered command names (including aliases).
     */
    public Set<String> getCommandNames() {
        return Collections.unmodifiableSet(commands.keySet());
    }

    public boolean hasCommand(String name) {
        String key = normalize(name);
        return key != null && commands.containsKey(key);
    }

    /**
     * Counts unique commands (aliases are de-duplicated).
     */
    public int getCommandCount() {
        Set<ExtensionCommand> unique = new HashSet<>();
        for (RegisteredCommand rc : commands.values()) {
            unique.add(rc.command);
        }
        return unique.size();
    }

    public Set<String> getExtensionCommandNames(String extensionId) {
        Set<String> names = extensionCommandNames.get(extensionId);
        return names != null ? Collections.unmodifiableSet(names) : Collections.emptySet();
    }

    private Set<String> collectNames(ExtensionCommand command) {
        Set<String> names = new LinkedHashSet<>();

        String primary = normalize(command.getName());
        if (primary != null) {
            names.add(primary);
        }

        List<String> aliases = command.getAliases();
        if (aliases != null) {
            for (String alias : aliases) {
                String a = normalize(alias);
                if (a != null) {
                    names.add(a);
                }
            }
        }

        return names;
    }

    private String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}


