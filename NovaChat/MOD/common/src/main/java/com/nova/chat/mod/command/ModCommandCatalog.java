package com.nova.chat.mod.command;

import com.nova.chat.mod.platform.CommandHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The mod subcommand set: one ordered list that both drives
 * {@code CommandManager} registration and renders {@code /nc help}.
 *
 * <p>These used to be written separately — {@code /nc help} hardcoded its lines
 * while the brigadier registrar registered the handlers — so a command added to
 * one side was silently missing from the other. That is exactly how the six
 * moderation commands shipped visible to brigadier but absent from help. Adding
 * a command to {@link #ENTRIES} now adds it to both.
 *
 * <p>{@link Entry#adminOnly()} mirrors the registrar's {@code .requires(isAdmin)}
 * gates: those lines are rendered only for a sender help sees as an admin, the
 * same coarse OP-level check that gates the commands themselves. A null
 * {@code helpKey} keeps a command out of help entirely (the hidden super-admin
 * {@code auth}), matching the references' {@code isHidden()}.
 */
public final class ModCommandCatalog {

    /**
     * One subcommand, or one help-only line, plus its help metadata.
     *
     * @param name      the registered subcommand name, or null for a help-only
     *                  line ({@code /nc <channel> <message>} shorthand)
     * @param factory   builds the handler registered for {@code name}, or null
     *                  for a help-only line
     * @param helpKey   the i18n key {@code /nc help} renders for this entry, or
     *                  null to keep it out of help (hidden command)
     * @param adminOnly whether only an admin sees the help line, mirroring the
     *                  command's own admin gate
     */
    public record Entry(String name, Supplier<CommandHandler> factory,
                        String helpKey, boolean adminOnly) {

        /** @return true when this entry registers a handler (not help-only) */
        public boolean isRegistered() {
            return factory != null;
        }

        /** @return true when this entry is never rendered in help */
        public boolean isHidden() {
            return helpKey == null;
        }
    }

    /** One rendered help line and the gate that decides who sees it. */
    public record HelpLine(String i18nKey, boolean adminOnly) {
    }

    /**
     * Registration + help order. Help renders these in listed order (minus
     * hidden entries), so the sequence here is the sequence players see.
     */
    public static final List<Entry> ENTRIES = List.of(
            new Entry("help", HelpCommand::new, "chat.command.help.line_help", false),
            new Entry("join", JoinCommand::new, "chat.command.help.line_join", false),
            new Entry("leave", LeaveCommand::new, "chat.command.help.line_leave", false),
            new Entry("list", ListCommand::new, "chat.command.help.line_list", false),
            new Entry("who", WhoCommand::new, "chat.command.help.line_who", false),
            new Entry("toggle", ToggleCommand::new, "chat.command.help.line_toggle", false),
            new Entry("ignore", IgnoreCommand::new, "chat.command.help.line_ignore", false),
            new Entry("unignore", UnignoreCommand::new, "chat.command.help.line_unignore", false),
            // /nc msg is the reference's private-message line; /nc r its reply line
            new Entry("msg", MsgCommand::new, "chat.command.help.line_pm", false),
            new Entry("r", ReplyCommand::new, "chat.command.help.line_reply", false),
            // /nc <channel> <message> shorthand — help copy only, no handler of its own
            new Entry(null, null, "chat.command.help.line_msg", false),
            // admin-gated, matching .requires(this::isAdmin) in the registrar;
            // the moderation block comes first, reload closes the list
            new Entry("mute", MuteCommand::new, "chat.command.help.line_mute", true),
            new Entry("kick", KickCommand::new, "chat.command.help.line_kick", true),
            new Entry("ban", BanCommand::new, "chat.command.help.line_ban", true),
            new Entry("unban", UnbanCommand::new, "chat.command.help.line_unban", true),
            new Entry("announce", AnnounceCommand::new, "chat.command.help.line_announce", true),
            new Entry("title", TitleCommand::new, "chat.command.help.line_title", true),
            new Entry("reload", ReloadCommand::new, "chat.command.help.line_reload", true),
            // hidden: registered and runnable, never listed (matching bukkit/folia)
            new Entry("auth", AuthCommand::new, null, true)
    );

    private ModCommandCatalog() {
        // Static catalog — no instances.
    }

    /**
     * @return every visible help line in catalog order, admin-gated ones
     *         included (the caller filters on the sender's admin flag)
     */
    public static List<HelpLine> helpLines() {
        List<HelpLine> lines = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.isHidden()) {
                continue;
            }
            lines.add(new HelpLine(entry.helpKey(), entry.adminOnly()));
        }
        return List.copyOf(lines);
    }
}
