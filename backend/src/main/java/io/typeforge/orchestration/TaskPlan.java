package io.typeforge.orchestration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable planning value, never an authorization grant. */
public record TaskPlan(UUID runId, int revision, long runEpoch, String goal,
                       Budget budget, List<Step> steps) {
    public TaskPlan { steps = List.copyOf(steps); }

    public record Budget(long maxTokens, @JsonProperty("maxWallMillis") long maxMillis, long maxCostMicros, int maxParallel) {}
    public record Step(String id, String role, List<String> dependencies,
                       Set<String> readScopes, Set<String> writeScopes,
                       Set<String> tools, boolean required, List<String> acceptance,
                       int maxModelSteps) {
        public Step {
            dependencies = List.copyOf(dependencies);
            readScopes = Set.copyOf(readScopes);
            writeScopes = Set.copyOf(writeScopes);
            tools = Set.copyOf(tools);
            acceptance = List.copyOf(acceptance);
        }
    }
}
