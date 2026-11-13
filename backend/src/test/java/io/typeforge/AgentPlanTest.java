package io.typeforge;

import io.typeforge.orchestration.PlanValidator;
import io.typeforge.orchestration.TaskPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentPlanTest {
    private final PlanValidator validator = new PlanValidator(Map.of("expert", Set.of("search", "compile")));
    private TaskPlan.Step step(String id, String... dependencies) {
        return new TaskPlan.Step(id, "expert", List.of(dependencies), Set.of(), Set.of(),
                Set.of("search"), true, List.of("citations verified"), 2);
    }
    private TaskPlan.Step scoped(String id, Set<String> read, Set<String> write) {
        return new TaskPlan.Step(id, "expert", List.of(), read, write, Set.of(), true, List.of("checked"), 0);
    }
    private TaskPlan plan(TaskPlan.Step... steps) {
        return new TaskPlan(UUID.randomUUID(), 1, 1, "explain PECS", new TaskPlan.Budget(1000, 10000, 20000, 3), List.of(steps));
    }
    @Test void ordersDependenciesDeterministically() {
        assertEquals(List.of("research", "code", "deck"), validator.validate(plan(step("deck", "code"), step("research"), step("code", "research"))));
    }
    @Test void rejectsDuplicateStepIds() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(step("same"), step("same"))));
    }
    @Test void rejectsMissingDependency() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(step("deck", "absent"))));
    }
    @Test void rejectsSelfDependency() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(step("deck", "deck"))));
    }
    @Test void rejectsCycleEvenInDisconnectedSubgraph() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(step("independent"), step("a", "b"), step("b", "a"))));
    }
    @Test void rejectsDuplicateDependency() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(step("a"), step("b", "a", "a"))));
    }
    @Test void rejectsUnknownRole() {
        var unknown = new TaskPlan.Step("a", "root", List.of(), Set.of(), Set.of(), Set.of(), true, List.of("ok"), 1);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(unknown)));
    }
    @Test void rejectsUnadmittedToolEvenForKnownRole() {
        var unsafe = new TaskPlan.Step("a", "expert", List.of(), Set.of(), Set.of(), Set.of("shell"), true, List.of("ok"), 1);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(unsafe)));
    }
    @Test void rejectsEmptyPlan() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan()));
    }
    @Test void rejectsOversizedPlan() {
        List<TaskPlan.Step> steps = new ArrayList<>();
        for (int i = 0; i < 25; i++) steps.add(step("s" + i));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(steps.toArray(TaskPlan.Step[]::new))));
    }
    @Test void acceptsTwentyFourStepBoundary() {
        List<TaskPlan.Step> steps = new ArrayList<>();
        for (int i = 0; i < 24; i++) steps.add(step("s" + i));
        assertEquals(24, validator.validate(plan(steps.toArray(TaskPlan.Step[]::new))).size());
    }
    @Test void rejectsUnboundedConcurrency() {
        var p = new TaskPlan(UUID.randomUUID(), 1, 1, "goal", new TaskPlan.Budget(100, 100, 100, 4), List.of(step("a")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(p));
    }
    @Test void rejectsNonpositiveBudget() {
        var p = new TaskPlan(UUID.randomUUID(), 1, 1, "goal", new TaskPlan.Budget(0, 100, 100, 1), List.of(step("a")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(p));
        var excessive = new TaskPlan(UUID.randomUUID(), 1, 1, "goal", new TaskPlan.Budget(100001, 10000, 2000001, 1), List.of(step("a")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(excessive));
    }
    @Test void rejectsMissingAcceptanceAndTooManyModelIterations() {
        var missing = new TaskPlan.Step("a", "expert", List.of(), Set.of(), Set.of(), Set.of(), true, List.of(), 1);
        var excessive = new TaskPlan.Step("a", "expert", List.of(), Set.of(), Set.of(), Set.of(), true, List.of("ok"), 7);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(missing)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(excessive)));
    }
    @Test void requiredWorkCannotDependOnSkippableWork() {
        var optional = new TaskPlan.Step("a", "expert", List.of(), Set.of(), Set.of(), Set.of(), false, List.of("ok"), 1);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(optional, step("b", "a"))));
    }
    @Test void onlyOptionalOutputsCannotCompleteGoal() {
        var optional = new TaskPlan.Step("a", "expert", List.of(), Set.of(), Set.of(), Set.of(), false, List.of("ok"), 1);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan(optional)));
    }
    @Test void readyBatchRespectsFanInAndParallelLimit() {
        var p = plan(step("a"), step("b"), step("c"), step("d"), step("merge", "a", "b"));
        assertEquals(List.of("a", "b", "c"), validator.selectReady(p, Set.of(), Set.of()));
        assertEquals(List.of("d", "merge"), validator.selectReady(p, Set.of("a", "b"), Set.of("c")));
    }
    @Test void sameArtifactWritesAreSerialized() {
        var p = plan(scoped("first", Set.of(), Set.of("artifact:one")), scoped("second", Set.of(), Set.of("artifact:one")), scoped("other", Set.of(), Set.of("artifact:two")));
        assertEquals(List.of("first", "other"), validator.selectReady(p, Set.of(), Set.of()));
    }
    @Test void writerWaitsForActiveReader() {
        var p = plan(scoped("reader", Set.of("artifact:one"), Set.of()), scoped("writer", Set.of(), Set.of("artifact:one")));
        assertTrue(validator.selectReady(p, Set.of(), Set.of("reader")).isEmpty());
    }
    @Test void readerWaitsForActiveWriter() {
        var p = plan(scoped("writer", Set.of(), Set.of("artifact:one")), scoped("reader", Set.of("artifact:one"), Set.of()));
        assertTrue(validator.selectReady(p, Set.of(), Set.of("writer")).isEmpty());
    }
    @Test void multipleReadersCanRunTogether() {
        assertEquals(List.of("a", "b"), validator.selectReady(plan(scoped("a", Set.of("x"), Set.of()), scoped("b", Set.of("x"), Set.of())), Set.of(), Set.of()));
    }
    @Test void rejectsUnknownOrOverlappingSchedulerState() {
        var p = plan(step("a"));
        assertThrows(IllegalArgumentException.class, () -> validator.selectReady(p, Set.of("absent"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> validator.selectReady(p, Set.of("a"), Set.of("a")));
    }
    @Test void rejectsInconsistentRecoveryState() {
        var p = plan(step("a"), step("b", "a"));
        assertThrows(IllegalArgumentException.class, () -> validator.selectReady(p, Set.of("b"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> validator.selectReady(p, Set.of(), Set.of("b")));
    }
    @Test void rejectsAlreadyConflictingLeases() {
        var p = plan(scoped("a", Set.of(), Set.of("x")), scoped("b", Set.of(), Set.of("x")));
        assertThrows(IllegalArgumentException.class, () -> validator.selectReady(p, Set.of(), Set.of("a", "b")));
    }
    @Test void inputCollectionsCannotMutatePlan() {
        var dependencies = new ArrayList<String>();
        var step = new TaskPlan.Step("a", "expert", dependencies, Set.of(), Set.of(), Set.of(), true, List.of("ok"), 1);
        dependencies.add("injected");
        assertTrue(step.dependencies().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> step.dependencies().add("injected"));
    }
}
