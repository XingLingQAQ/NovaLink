package com.nova.link.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Security configuration section.
 * 
 * Requirements: 1.5 - IP ban mechanism
 */
public class SecurityConfig {

    private List<String> allowedIps = new ArrayList<>();
    private int ipBanDuration = 300; // seconds

    public SecurityConfig() {}

    public List<String> getAllowedIps() {
        return allowedIps;
    }

    public void setAllowedIps(List<String> allowedIps) {
        this.allowedIps = allowedIps != null ? new ArrayList<>(allowedIps) : new ArrayList<>();
    }

    public int getIpBanDuration() {
        return ipBanDuration;
    }

    public void setIpBanDuration(int ipBanDuration) {
        this.ipBanDuration = ipBanDuration > 0 ? ipBanDuration : 300;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SecurityConfig that = (SecurityConfig) o;
        return ipBanDuration == that.ipBanDuration &&
               Objects.equals(allowedIps, that.allowedIps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowedIps, ipBanDuration);
    }
}
