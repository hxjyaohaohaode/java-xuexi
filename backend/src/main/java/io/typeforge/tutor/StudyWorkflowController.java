package io.typeforge.tutor;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class StudyWorkflowController {
    private final StudyWorkflowService service;private final IdentityService users;private final SchemaCatalog schemas;private final JsonCodec json;
    public StudyWorkflowController(StudyWorkflowService service,IdentityService users,SchemaCatalog schemas,JsonCodec json){this.service=service;this.users=users;this.schemas=schemas;this.json=json;}
    @PostMapping("/workspaces/{id}/agent-runs") @ResponseStatus(HttpStatus.ACCEPTED)
    public DurableAgentRuntime.RunView create(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key){schemas.validate("StudyRunInput",input);return service.create(users.session(principal.getName()).userId(),id,json.read(json.write(input),StudyWorkflowService.Input.class),key);}
    @PostMapping("/workspaces/{id}/notebooks") @ResponseStatus(HttpStatus.ACCEPTED)
    public DurableAgentRuntime.RunView notebook(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("NotebookInput",input);
        return service.create(users.session(principal.getName()).userId(),id,new StudyWorkflowService.Input(UUID.fromString(input.path("goalId").asText()),UUID.fromString(input.path("snapshotId").asText()),List.of("NOTEBOOK"),json.read(input.path("budget").toString(),io.typeforge.orchestration.TaskPlan.Budget.class),input.has("mode")?TutorService.Mode.valueOf(input.path("mode").asText()):TutorService.Mode.EVIDENCE_ONLY,List.of(json.read(input.path("attemptIds").toString(),UUID[].class))),key);
    }
    @GetMapping("/workspaces/{id}/agent-runs") public List<DurableAgentRuntime.RunView> list(Principal principal,@PathVariable UUID id){return service.list(users.session(principal.getName()).userId(),id);}
    @GetMapping("/agent-runs/{id}/study") public StudyWorkflowService.Details details(Principal principal,@PathVariable UUID id){return service.details(users.session(principal.getName()).userId(),id);}
}
