package io.typeforge.identity;

import java.util.UUID;

/**
 * Module-owned account withdrawal. Invoked inside the closure transaction after the account and all
 * its workspaces are locked, before the identity is disabled. Implementations must not cross owners
 * or perform external I/O; an exception rolls back the complete closure.
 */
public interface AccountClosureParticipant {
    default void closeWorkspace(UUID owner,UUID workspace,boolean active) {}
    default void closeAccount(UUID owner) {}
}
