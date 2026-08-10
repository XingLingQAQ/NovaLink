package com.nova.link.config;

import java.util.Objects;

/**
 * Feature toggles exposed on the web panel's Settings page.
 *
 * <p>Each switch is backed by a {@code volatile} flag on the runtime component
 * it controls (SensitiveWordFilter / MessagePipeline), so flipping a switch
 * here takes effect immediately without a full restart. The Settings REST
 * endpoints ({@code GET/PUT /api/settings}) read and mutate this object, then
 * {@link ConfigManager#save()} persists it back to {@code novalink.yml} and the
 * reload listeners re-apply the volatile flags.
 *
 * <p>Requirements: Settings page backend (FeatureConfig + hot-reload)
 */
public class FeatureConfig {

    /** Whether the sensitive-word filter is active. Default true. */
    private boolean filterEnabled = true;

    /** Whether chat messages are written to the chat log. Default false. */
    private boolean messageLogEnabled = false;

    /** Whether cross-server chat fan-out is active. Default true. */
    private boolean crossServerChatEnabled = true;

    public FeatureConfig() {}

    public boolean isFilterEnabled() {
        return filterEnabled;
    }

    public void setFilterEnabled(boolean filterEnabled) {
        this.filterEnabled = filterEnabled;
    }

    public boolean isMessageLogEnabled() {
        return messageLogEnabled;
    }

    public void setMessageLogEnabled(boolean messageLogEnabled) {
        this.messageLogEnabled = messageLogEnabled;
    }

    public boolean isCrossServerChatEnabled() {
        return crossServerChatEnabled;
    }

    public void setCrossServerChatEnabled(boolean crossServerChatEnabled) {
        this.crossServerChatEnabled = crossServerChatEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FeatureConfig that = (FeatureConfig) o;
        return filterEnabled == that.filterEnabled &&
               messageLogEnabled == that.messageLogEnabled &&
               crossServerChatEnabled == that.crossServerChatEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(filterEnabled, messageLogEnabled, crossServerChatEnabled);
    }
}
