package io.typeforge.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.shared.DomainException;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{id}/courseware/{conceptId}")
public class CoursewareController {
    private final PersonalCourseware lessons;
    private final IdentityService users;
    public CoursewareController(PersonalCourseware lessons, IdentityService users) {
        this.lessons = lessons;
        this.users = users;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public PersonalCourseware.Plan generate(Principal principal, @PathVariable UUID id, @PathVariable UUID conceptId,
            @RequestBody JsonNode input, @RequestHeader("Idempotency-Key") String key) {
        if (!input.isObject() || input.size() > 2) throw DomainException.invalid("Invalid courseware settings");
        input.fieldNames().forEachRemaining(name -> {
            if (!Set.of("snapshotId", "approach").contains(name) || !input.get(name).isTextual())
                throw DomainException.invalid("Invalid courseware setting");
        });
        UUID snapshot;
        try { snapshot = input.has("snapshotId") ? UUID.fromString(input.get("snapshotId").asText()) : null; }
        catch (IllegalArgumentException failure) { throw DomainException.invalid("Invalid snapshot identifier"); }
        return lessons.generate(users.session(principal.getName()).userId(), id, conceptId, snapshot,
            input.path("approach").asText("AUTO"), key);
    }

    @GetMapping
    public ResponseEntity<PersonalCourseware.Plan> get(Principal principal, @PathVariable UUID id,
            @PathVariable UUID conceptId, @RequestParam(required=false) UUID planId,
            @RequestParam(defaultValue="false") boolean optional) {
        var result = lessons.lookup(users.session(principal.getName()).userId(), id, conceptId, planId);
        if (result.isPresent()) return ResponseEntity.ok(result.get());
        if (optional && planId == null) return ResponseEntity.noContent().build();
        throw DomainException.missing();
    }
}
