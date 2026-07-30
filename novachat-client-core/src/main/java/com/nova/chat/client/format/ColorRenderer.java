package com.nova.chat.client.format;

/**
 * Platform-supplied function that converts a legacy color-coded string (carrying
 * raw {@code &}/{@code §} codes, with hex already expanded) into the platform's
 * native rendered object.
 *
 * <p>Each platform injects an implementation appropriate to its color API:
 * <ul>
 *   <li>Velocity / Sponge (Adventure) — {@code LegacyComponentSerializer.deserialize}</li>
 *   <li>Bungee — {@code ChatColor.translateAlternateColorCodes} + {@code TextComponent.fromLegacyText}</li>
 *   <li>Bukkit / Folia / MultiPaper — {@code ChatColor.translateAlternateColorCodes} (returns String)</li>
 *   <li>Nukkit / PNX — {@code TextFormat.colorize} (returns String)</li>
 * </ul>
 *
 * <p>This is the single platform-specific seam in the otherwise-agnostic
 * {@link MessageFormatService} pipeline.
 *
 * @param <T> the platform rendered type (e.g. Adventure {@code Component},
 *            Bungee {@code BaseComponent[]}, or {@code String})
 */
@FunctionalInterface
public interface ColorRenderer<T> {

    /**
     * Renders a legacy color-coded string into the platform's native object.
     *
     * @param legacyText text with raw color codes (hex already expanded by
     *                   {@link MessageFormatService#convertHexToSection} or
     *                   {@link MessageFormatService#convertHexToAmpersand});
     *                   may be null — implementations should return an empty
     *                   rendered value in that case
     * @return the platform-rendered object, never null
     */
    T render(String legacyText);
}
