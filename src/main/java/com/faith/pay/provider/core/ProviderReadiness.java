package com.faith.pay.provider.core;

/** Optional preflight hook for local provider configuration before reserving a money operation. */
public interface ProviderReadiness {
    default void validateConfiguration() { }
}
