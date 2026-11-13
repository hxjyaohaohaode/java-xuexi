package io.typeforge.orchestration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic preflight only. DB transactions must enforce leases, quotas and ownership. */
public final class PlanValidator {
    private final Map<String, Set<String>> roleTools;

    public PlanValidator(Map<String, Set<String>> roleTools) {
        Map<String, Set<String>> copy = new HashMap<>();
        roleTools.forEach((role, tools) -> copy.put(role, Set.copyOf(tools)));
        this.roleTools = Map.copyOf(copy);
    }

    public List<String> validate(TaskPlan plan) {
        if (plan == null || plan.runId() == null || plan.revision() < 1 || plan.runEpoch() < 1
                || plan.goal() == null || plan.goal().isBlank() || plan.goal().length() > 4000) {
            throw new IllegalArgumentException("invalid plan identity or goal");
        }
        var budget = plan.budget();
        if (budget == null || budget.maxTokens() < 1 || budget.maxTokens() > 100000
                || budget.maxMillis() < 1000 || budget.maxMillis() > 900000
                || budget.maxCostMicros() < 1 || budget.maxCostMicros() > 2000000
                || budget.maxParallel() < 1 || budget.maxParallel() > 3) {
            throw new IllegalArgumentException("invalid or excessive budget");
        }
        if (plan.steps().isEmpty() || plan.steps().size() > 24) {
            throw new IllegalArgumentException("plan must have 1..24 steps");
        }
        Map<String, TaskPlan.Step> byId = new LinkedHashMap<>();
        boolean hasRequired = false;
        for (var step : plan.steps()) {
            if (step.id() == null || !step.id().matches("[a-z][a-z0-9-]{0,63}")
                    || byId.putIfAbsent(step.id(), step) != null) {
                throw new IllegalArgumentException("invalid or duplicate step id");
            }
            if (step.role() == null || !roleTools.containsKey(step.role()) || !roleTools.get(step.role()).containsAll(step.tools())) {
                throw new IllegalArgumentException("role or capability not admitted");
            }
            if (step.maxModelSteps() < 0 || step.maxModelSteps() > 6 || step.acceptance().isEmpty()
                    || step.acceptance().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("missing acceptance or excessive model iterations");
            }
            if (step.dependencies().size() != new HashSet<>(step.dependencies()).size()
                    || step.readScopes().stream().anyMatch(String::isBlank)
                    || step.writeScopes().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("invalid dependencies or resource scopes");
            }
            hasRequired |= step.required();
        }
        if (!hasRequired) throw new IllegalArgumentException("at least one required output is needed");
        for (var step : plan.steps()) {
            for (var dependency : step.dependencies()) {
                if (!byId.containsKey(dependency) || dependency.equals(step.id())) {
                    throw new IllegalArgumentException("missing or self dependency");
                }
                if (step.required() && !byId.get(dependency).required()) {
                    throw new IllegalArgumentException("required work cannot depend on skippable work");
                }
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (order.size() < byId.size()) {
            boolean progressed = false;
            for (var step : byId.values()) {
                if (!done.contains(step.id()) && done.containsAll(step.dependencies())) {
                    done.add(step.id());
                    order.add(step.id());
                    progressed = true;
                }
            }
            if (!progressed) throw new IllegalArgumentException("cyclic task graph");
        }
        return List.copyOf(order);
    }

    /** Ready candidates; claiming a returned step still requires a transaction and epoch check. */
    public List<String> selectReady(TaskPlan plan, Set<String> succeeded, Set<String> inFlight) {
        List<String> order = validate(plan);
        Set<String> known = Set.copyOf(order);
        if (!known.containsAll(succeeded) || !known.containsAll(inFlight)
                || succeeded.stream().anyMatch(inFlight::contains) || inFlight.size() > plan.budget().maxParallel()) {
            throw new IllegalArgumentException("invalid scheduler state");
        }
        Map<String, TaskPlan.Step> byId = new HashMap<>();
        plan.steps().forEach(s -> byId.put(s.id(), s));
        for (String id : succeeded) {
            if (!succeeded.containsAll(byId.get(id).dependencies())) {
                throw new IllegalArgumentException("succeeded state is not dependency closed");
            }
        }
        List<TaskPlan.Step> held = new ArrayList<>();
        for (String id : order) {
            if (inFlight.contains(id)) {
                var step = byId.get(id);
                if (!succeeded.containsAll(step.dependencies()) || held.stream().anyMatch(s -> conflicts(s, step))) {
                    throw new IllegalArgumentException("in-flight state violates dependency or resource scope");
                }
                held.add(step);
            }
        }
        List<String> selected = new ArrayList<>();
        for (String id : order) {
            if (held.size() >= plan.budget().maxParallel()) break;
            var step = byId.get(id);
            if (!succeeded.contains(id) && !inFlight.contains(id) && succeeded.containsAll(step.dependencies())
                    && held.stream().noneMatch(s -> conflicts(s, step))) {
                held.add(step);
                selected.add(id);
            }
        }
        return List.copyOf(selected);
    }

    private static boolean conflicts(TaskPlan.Step a, TaskPlan.Step b) {
        return a.writeScopes().stream().anyMatch(s -> b.writeScopes().contains(s) || b.readScopes().contains(s))
                || b.writeScopes().stream().anyMatch(a.readScopes()::contains);
    }
}
