package com.antondev.keys.integration.core;

public interface CoreBridge {
    String LEGACY_API_RANGE = ">=1.0 <2.0";
    String RUNTIME_API_RANGE = ">=2.0 <3.0";
    String SUPPORTED_API_RANGE = LEGACY_API_RANGE + " | " + RUNTIME_API_RANGE;
    String MODULE_ID = "keys";

    boolean installed();
    boolean available();
    boolean compatible();
    String pluginVersion();
    String apiVersion();
    String mode();
    String registrationState();
    String detail();
    void registerStarting();
    void markReady(String detail);
    void markDegraded(String detail);
    void markFailed(String detail);
    void unregister();
    ProviderHint providerHint(String integrationId);

    enum ProviderHint {
        PRESENT,
        MISSING,
        DISABLED,
        UNKNOWN
    }
}
