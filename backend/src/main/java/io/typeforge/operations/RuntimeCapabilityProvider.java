package io.typeforge.operations;

import java.util.Set;

/** Adapter configuration metadata only; request authorization still runs in the domain service. */
public interface RuntimeCapabilityProvider {
    Set<String> enabledCapabilities();
}
