package io.typeforge.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.shared.SchemaCatalog;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LearningController {
    private final LearningService learning;
    private final IdentityService users;
    private final SchemaCatalog schemas;
    public LearningController(LearningService learning,IdentityService users,SchemaCatalog schemas) { this.learning=learning;this.users=users;this.schemas=schemas; }
    private UUID owner(Principal principal) { return users.session(principal.getName()).userId(); }
    @PostMapping("/attempts") @ResponseStatus(HttpStatus.CREATED)
    public LearningService.Attempt submit(Principal principal,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("AttemptInput",input);
        return learning.submit(owner(principal),UUID.fromString(input.path("workspaceId").asText()),UUID.fromString(input.path("releaseId").asText()),UUID.fromString(input.path("exerciseId").asText()),input.path("exerciseRevision").asInt(),input.path("answer"),key);
    }
    @GetMapping("/attempts/{id}") public LearningService.Attempt get(Principal principal,@PathVariable UUID id) { return learning.get(owner(principal),id); }
    @GetMapping("/me/progress") public LearningService.Progress progress(Principal principal) { return learning.progress(owner(principal)); }
    @GetMapping("/me/reviews") public List<LearningService.Review> reviews(Principal principal) { return learning.reviews(owner(principal)); }
    @PatchMapping("/me/reviews/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input) {
        schemas.validate("ReviewDismissInput",input);
        learning.dismissReview(owner(principal),id);
    }
}
