package io.typeforge.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.identity.IdentityService;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotebookController {
    private final NotebookService notebooks;
    private final IdentityService users;
    private final SchemaCatalog schemas;
    private final JsonCodec json;
    private final NotebookActions actions;
    private final NotebookInteractionService interactions;
    public NotebookController(NotebookService notebooks,IdentityService users,SchemaCatalog schemas,JsonCodec json,NotebookActions actions,NotebookInteractionService interactions) {
        this.notebooks=notebooks;this.users=users;this.schemas=schemas;this.json=json;this.actions=actions;this.interactions=interactions;
    }
    @PostMapping("/workspaces/{id}/notebook-drafts") @ResponseStatus(HttpStatus.CREATED)
    public ArtifactService.Artifact notebook(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("NotebookDraftInput",input);
        return notebooks.build(users.session(principal.getName()).userId(),id,List.of(json.read(input.path("attemptIds").toString(),UUID[].class)),input.path("title").asText(),key);
    }
    @PostMapping("/notebooks/{id}/actions")
    public NotebookActions.Result action(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        return actions.dispatch(users.session(principal.getName()).userId(),id,input,key);
    }
    @GetMapping("/notebooks/{id}/state")
    public NotebookInteractionService.State state(Principal principal,@PathVariable UUID id,@RequestParam int artifactRevision) {
        return interactions.state(users.session(principal.getName()).userId(),id,artifactRevision);
    }
    @GetMapping("/notebooks/{id}/block-options")
    public NotebookInteractionService.Options options(Principal principal,@PathVariable UUID id,@RequestParam int artifactRevision,@RequestParam String originBlockId) {
        return interactions.options(users.session(principal.getName()).userId(),id,artifactRevision,originBlockId);
    }
    @PostMapping("/notebooks/{id}/blocks") @ResponseStatus(HttpStatus.CREATED)
    public ArtifactService.Version append(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        return interactions.append(users.session(principal.getName()).userId(),id,input,key);
    }
}
