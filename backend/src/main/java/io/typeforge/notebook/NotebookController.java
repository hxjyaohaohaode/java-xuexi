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
    public NotebookController(NotebookService notebooks,IdentityService users,SchemaCatalog schemas,JsonCodec json) {
        this.notebooks=notebooks;this.users=users;this.schemas=schemas;this.json=json;
    }
    @PostMapping("/workspaces/{id}/notebook-drafts") @ResponseStatus(HttpStatus.CREATED)
    public ArtifactService.Artifact notebook(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("NotebookDraftInput",input);
        return notebooks.build(users.session(principal.getName()).userId(),id,List.of(json.read(input.path("attemptIds").toString(),UUID[].class)),input.path("title").asText(),key);
    }
}
