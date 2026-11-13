package io.typeforge.curriculum;

import io.typeforge.identity.IdentityService;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {
    private final CurriculumService curriculum;
    private final IdentityService users;
    public KnowledgeController(CurriculumService curriculum,IdentityService users) { this.curriculum=curriculum;this.users=users; }
    private void authorize(Principal principal) { users.session(principal.getName()); }
    @GetMapping("/releases/active") public Map<String,UUID> active(Principal principal) { authorize(principal);return Map.of("releaseId",curriculum.activeRelease()); }
    @GetMapping("/concepts") public Map<String,Object> concepts(Principal principal,@RequestParam UUID releaseId) { authorize(principal);return Map.of("releaseId",releaseId,"items",curriculum.concepts(releaseId)); }
    @GetMapping("/concepts/{id}") public CurriculumService.ConceptView concept(Principal principal,@PathVariable UUID id,@RequestParam UUID releaseId) { authorize(principal);return curriculum.concept(releaseId,id); }
    @GetMapping("/exercises") public CurriculumService.ExercisePage exercises(Principal principal,@RequestParam UUID releaseId,@RequestParam(required=false) UUID conceptId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) { authorize(principal);return curriculum.exercises(releaseId,conceptId,cursor,limit); }
    @GetMapping("/exercises/{id}") public CurriculumService.ExerciseView exercise(Principal principal,@PathVariable UUID id,@RequestParam UUID releaseId,@RequestParam(defaultValue="1") int revision) { authorize(principal);return curriculum.exercise(releaseId,id,revision); }
}
