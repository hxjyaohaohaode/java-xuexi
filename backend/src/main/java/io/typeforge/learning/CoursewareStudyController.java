package io.typeforge.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.orchestration.DurableAgentRuntime.RunView;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.tutor.TutorService.Mode;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{id}/courseware/{conceptId}/study")
public class CoursewareStudyController {
    private final CoursewareStudyService service;
    private final IdentityService users;
    private final SchemaCatalog schemas;

    public CoursewareStudyController(CoursewareStudyService service,IdentityService users,SchemaCatalog schemas) {
        this.service=service; this.users=users; this.schemas=schemas;
    }

    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView start(Principal principal,@PathVariable UUID id,@PathVariable UUID conceptId,
            @RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("CoursewareStudyInput",input);
        return service.start(users.session(principal.getName()).userId(),id,conceptId,
            UUID.fromString(input.path("planId").asText()),Mode.valueOf(input.path("mode").asText()),key);
    }

    @GetMapping
    public ResponseEntity<RunView> resume(Principal principal,@PathVariable UUID id,@PathVariable UUID conceptId,
            @RequestParam UUID planId,@RequestParam Mode mode) {
        return service.resume(users.session(principal.getName()).userId(),id,conceptId,planId,mode)
            .map(ResponseEntity::ok).orElseGet(()->ResponseEntity.noContent().build());
    }
}
