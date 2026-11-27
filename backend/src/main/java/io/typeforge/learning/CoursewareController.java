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
        if (!input.isObject() || input.size() > 3) throw DomainException.invalid("Invalid courseware settings");
        input.fieldNames().forEachRemaining(name -> {
            if (!Set.of("snapshotId", "approach", "brief").contains(name) || (!name.equals("brief") && !input.get(name).isTextual()))
                throw DomainException.invalid("Invalid courseware setting");
        });
        PersonalCourseware.Brief brief=null;
        if (input.has("brief")) {
            var settings=input.get("brief");
            if (!settings.isObject() || settings.size()!=2 || !settings.path("interest").isTextual() || !settings.path("requirements").isTextual())
                throw DomainException.invalid("The lesson brief needs interest and requirements");
            brief=new PersonalCourseware.Brief(settings.get("interest").textValue(),settings.get("requirements").textValue());
        }
        UUID snapshot;
        try { snapshot = input.has("snapshotId") ? UUID.fromString(input.get("snapshotId").asText()) : null; }
        catch (IllegalArgumentException failure) { throw DomainException.invalid("Invalid snapshot identifier"); }
        return lessons.generate(users.session(principal.getName()).userId(), id, conceptId, snapshot,
            input.path("approach").asText("AUTO"), brief, key);
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
