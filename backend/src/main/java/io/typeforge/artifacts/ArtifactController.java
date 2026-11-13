package io.typeforge.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ArtifactController {
    private final ArtifactService artifacts;
    private final IdentityService users;
    private final SchemaCatalog schemas;
    private final JsonCodec json;
    public ArtifactController(ArtifactService artifacts,IdentityService users,SchemaCatalog schemas,JsonCodec json) {this.artifacts=artifacts;this.users=users;this.schemas=schemas;this.json=json;}
    private UUID owner(Principal principal) { return users.session(principal.getName()).userId(); }
    @GetMapping("/workspaces/{id}/artifacts") public List<ArtifactService.Artifact> list(Principal principal,@PathVariable UUID id) {return artifacts.list(owner(principal),id);}
    @PostMapping("/workspaces/{id}/artifacts") @ResponseStatus(HttpStatus.CREATED)
    public ArtifactService.Artifact create(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("ArtifactInput",input);
        UUID goal=input.hasNonNull("goalId")?UUID.fromString(input.path("goalId").asText()):null;
        return artifacts.create(owner(principal),id,input.path("kind").asText(),input.path("title").asText(),goal,key);
    }
    @GetMapping("/artifacts/{id}") public ArtifactService.Artifact get(Principal principal,@PathVariable UUID id) {return artifacts.get(owner(principal),id);}
    @GetMapping("/artifacts/{id}/revisions") public ArtifactService.Version revision(Principal principal,@PathVariable UUID id,@RequestParam int revision) {return artifacts.version(owner(principal),id,revision);}
    @PostMapping("/artifacts/{id}/revisions") @ResponseStatus(HttpStatus.CREATED)
    public ArtifactService.Version save(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input) {
        schemas.validate("ArtifactRevisionInput",input);
        return artifacts.save(owner(principal),id,input.path("baseRevision").asInt(),input.path("content"),List.of(json.read(input.path("inputRefs").toString(),Reference[].class)));
    }
}
