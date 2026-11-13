package io.typeforge.identity;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
public record Actor(UUID userId, Set<Role> roles) {
    public enum Role { LEARNER, EDITOR, MAINTAINER }
    public Actor { Objects.requireNonNull(userId); roles = Set.copyOf(roles); }
}
