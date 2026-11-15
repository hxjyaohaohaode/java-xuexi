package io.typeforge.tutor;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceService;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RunController {
    private final DurableAgentRuntime runtime;
    private final EvidenceWorkflow workflow;
    private final WorkspaceService workspaces;
    private final IdentityService users;
    private final SchemaCatalog schemas;
    private final StudyWorkflowService studies;
    public RunController(DurableAgentRuntime runtime,EvidenceWorkflow workflow,WorkspaceService workspaces,IdentityService users,SchemaCatalog schemas,StudyWorkflowService studies) { this.runtime=runtime;this.workflow=workflow;this.workspaces=workspaces;this.users=users;this.schemas=schemas;this.studies=studies; }
    private UUID owner(Principal principal) { return users.session(principal.getName()).userId(); }
    @PostMapping("/workspaces/{id}/evidence-runs") @ResponseStatus(HttpStatus.ACCEPTED)
    public DurableAgentRuntime.RunView evidence(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("EvidenceRunInput",input);
        return workflow.create(owner(principal),id,UUID.fromString(input.path("goalId").asText()),UUID.fromString(input.path("snapshotId").asText()),input.path("query").asText(),key);
    }
    @GetMapping("/agent-runs/{id}") public DurableAgentRuntime.RunView get(Principal principal,@PathVariable UUID id) {
        UUID owner=owner(principal);return runtime.get(owner,workspaces.workspaceFor(owner,"RUN",id),id);
    }
    @GetMapping("/agent-runs/{id}/events") public List<DurableAgentRuntime.RunEvent> events(Principal principal,@PathVariable UUID id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit) {
        UUID owner=owner(principal);return runtime.events(owner,workspaces.workspaceFor(owner,"RUN",id),id,after,limit);
    }
    @PostMapping("/agent-runs/{id}/commands") @ResponseStatus(HttpStatus.ACCEPTED)
    public DurableAgentRuntime.RunView command(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input) {
        schemas.validate("RunCommand",input);
        UUID owner=owner(principal),workspace=workspaces.workspaceFor(owner,"RUN",id);
        if(input.path("kind").asText().equals("STEER")) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"A configured planning adapter is required for natural-language steering");
        var run=runtime.get(owner,workspace,id);
        if(run.planRevision()!=input.path("expectedPlanRevision").asInt()) throw DomainException.conflict("Plan changed before command submission");
        var result=runtime.command(owner,workspace,id,input.path("expectedRunEpoch").asLong(),DurableAgentRuntime.Command.valueOf(input.path("kind").asText()));
        if(Set.of("PAUSE","CANCEL").contains(input.path("kind").asText())) studies.cancelAssociated(owner,id);
        return result;
    }
}
