package com.antondev.keys.integration.core;

final class StandaloneCoreBridge implements CoreBridge {
    private final boolean installed;
    private final String pluginVersion;
    private final String apiVersion;
    private final String detail;

    StandaloneCoreBridge(boolean installed, String pluginVersion, String apiVersion, String detail) {
        this.installed = installed;
        this.pluginVersion = pluginVersion == null || pluginVersion.isBlank() ? "-" : pluginVersion;
        this.apiVersion = apiVersion == null || apiVersion.isBlank() ? "-" : apiVersion;
        this.detail = detail == null || detail.isBlank() ? "PlexonCore is not installed" : detail;
    }

    @Override public boolean installed() { return installed; }
    @Override public boolean available() { return false; }
    @Override public boolean compatible() { return false; }
    @Override public String pluginVersion() { return pluginVersion; }
    @Override public String apiVersion() { return apiVersion; }
    @Override public String mode() { return "STANDALONE"; }
    @Override public String registrationState() { return installed ? "UNAVAILABLE" : "NOT_INSTALLED"; }
    @Override public String detail() { return detail; }
    @Override public void registerStarting() {}
    @Override public void markReady(String detail) {}
    @Override public void markDegraded(String detail) {}
    @Override public void markFailed(String detail) {}
    @Override public void unregister() {}
    @Override public ProviderHint providerHint(String integrationId) { return ProviderHint.UNKNOWN; }
}
