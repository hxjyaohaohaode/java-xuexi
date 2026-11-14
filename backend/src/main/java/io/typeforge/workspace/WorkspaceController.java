package io.typeforge.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private final WorkspaceService workspaces;
    private final IdentityService users;
    private final SchemaCatalog schemas;
    private final JsonCodec json;
    public WorkspaceController(WorkspaceService workspaces,IdentityService users,SchemaCatalog schemas,JsonCodec json) { this.workspaces=workspaces;this.users=users;this.schemas=schemas;this.json=json; }
    private UUID owner(Principal principal) { return users.session(principal.getName()).userId(); }
    @PostMapping("/workspaces") @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceService.Workspace create(Principal principal,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("WorkspaceInput",input);
        return workspaces.create(owner(principal),UUID.fromString(input.path("releaseId").asText()),input.path("title").asText(),input.path("guidanceMode").asText(),key);
    }
    @GetMapping("/workspaces") public List<WorkspaceService.Workspace> list(Principal principal,@RequestParam(defaultValue="100") int limit) { return workspaces.list(owner(principal),limit); }
    @GetMapping("/workspaces/{id}") public WorkspaceService.Workspace get(Principal principal,@PathVariable UUID id) { return workspaces.get(owner(principal),id); }
    @PatchMapping("/workspaces/{id}") public WorkspaceService.Workspace update(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input) {
        schemas.validate("WorkspaceUpdate",input);
        return workspaces.update(owner(principal),id,input.path("expectedRevision").asInt(),input.path("title").asText(),input.path("guidanceMode").asText());
    }
    @PostMapping("/workspaces/{id}/goals") @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceService.Goal goal(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("GoalInput",input);
        return workspaces.createGoal(owner(principal),id,input.path("text").asText(),List.of(json.read(input.path("acceptance").toString(),String[].class)),key);
    }
    @GetMapping("/workspaces/{id}/goals") public List<WorkspaceService.Goal> goals(Principal principal,@PathVariable UUID id) {return workspaces.goals(owner(principal),id);}
    @PatchMapping("/goals/{id}") public WorkspaceService.Goal updateGoal(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input) {
        schemas.validate("GoalUpdate",input);
        return workspaces.updateGoal(owner(principal),id,input.path("expectedRevision").asInt(),input.path("text").asText(),List.of(json.read(input.path("acceptance").toString(),String[].class)),input.path("status").asText());
    }
    @PostMapping("/workspaces/{id}/context") @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceService.Context snapshot(Principal principal,@PathVariable UUID id) { return workspaces.createContext(owner(principal),id); }
    @PostMapping("/workspaces/{id}/events") @ResponseStatus(HttpStatus.CREATED)
    public EventJournal.Event event(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input) {
        schemas.validate("WorkspaceEventInput",input);
        return workspaces.recordClientEvent(owner(principal),id,UUID.fromString(input.path("id").asText()),input.path("type").asText(),List.of(json.read(input.path("references").toString(),WorkspaceContextStore.Reference[].class)),input.path("expectedRevision").asInt());
    }
    @GetMapping("/workspaces/{id}/events")
    public List<EventJournal.Event> events(Principal principal,@PathVariable UUID id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit) { return workspaces.events(owner(principal),id,after,limit); }
    @GetMapping("/workspaces/{id}/projection") public WorkspaceService.Projection projection(Principal principal,@PathVariable UUID id) { return workspaces.projection(owner(principal),id); }
    @GetMapping("/workspaces/{id}/memories") public List<WorkspaceService.Memory> memories(Principal principal,@PathVariable UUID id) { return workspaces.memories(owner(principal),id); }
    @PostMapping("/workspaces/{id}/memories") @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceService.Memory memory(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("MemoryInput",input);
        return workspaces.stateMemory(owner(principal),id,input.path("summary").asText(),key);
    }
    @PostMapping("/memories/{id}/revoke") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input) {
        schemas.validate("MemoryRevoke",input);
        UUID owner=owner(principal);
        workspaces.revokeMemory(owner,workspaces.workspaceFor(owner,"MEMORY",id),id,input.path("expectedRevision").asInt());
    }
}
