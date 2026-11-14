package io.typeforge.publishing;

import static io.typeforge.publishing.ContentWorkbenchModels.*;

import io.typeforge.identity.IdentityService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public final class ContentWorkbenchController {
    private final IdentityService identities;
    private final ContentDraftService drafts;
    private final ReleaseWorkbenchService releases;
    public ContentWorkbenchController(IdentityService identities,ContentDraftService drafts,ReleaseWorkbenchService releases) { this.identities=identities;this.drafts=drafts;this.releases=releases; }
    private UUID actor(Principal principal) { return identities.session(principal.getName()).userId(); }
    @GetMapping("/concepts") public List<Concept> concepts(Principal principal,@RequestParam(defaultValue="100") int limit) { return drafts.concepts(actor(principal),limit); }
    @PostMapping("/concepts") @ResponseStatus(HttpStatus.CREATED)
    public Concept concept(Principal principal,@RequestBody ConceptInput input,@RequestHeader("Idempotency-Key") String key) { return drafts.createConcept(actor(principal),input,key); }
    @GetMapping("/chunks") public List<DraftSummary> chunks(Principal principal,@RequestParam(required=false) String status,@RequestParam(defaultValue="50") int limit) { return drafts.drafts(actor(principal),"CHUNK",status,limit); }
    @PostMapping("/chunks") @ResponseStatus(HttpStatus.CREATED)
    public Chunk chunk(Principal principal,@RequestBody ChunkInput input,@RequestHeader("Idempotency-Key") String key) { return drafts.createChunk(actor(principal),input,key); }
    @GetMapping("/chunks/{id}/revisions/{revision}") public Chunk chunk(Principal principal,@PathVariable UUID id,@PathVariable int revision) { return drafts.chunk(actor(principal),id,revision); }
    @GetMapping("/exercises") public List<DraftSummary> exercises(Principal principal,@RequestParam(required=false) String status,@RequestParam(defaultValue="50") int limit) { return drafts.drafts(actor(principal),"EXERCISE",status,limit); }
    @PostMapping("/exercises") @ResponseStatus(HttpStatus.CREATED)
    public Exercise exercise(Principal principal,@RequestBody ExerciseInput input,@RequestHeader("Idempotency-Key") String key) { return drafts.createExercise(actor(principal),input,key); }
    @GetMapping("/exercises/{id}/revisions/{revision}") public Exercise exercise(Principal principal,@PathVariable UUID id,@PathVariable int revision) { return drafts.exercise(actor(principal),id,revision); }
    @GetMapping("/reviews") public List<Review> reviews(Principal principal,@RequestParam(defaultValue="50") int limit) { return drafts.reviews(actor(principal),limit); }
    @PostMapping("/reviews") @ResponseStatus(HttpStatus.CREATED)
    public Review review(Principal principal,@RequestBody ReviewInput input,@RequestHeader("Idempotency-Key") String key) { return drafts.review(actor(principal),input,key); }
    @GetMapping("/reviews/{id}") public Review review(Principal principal,@PathVariable UUID id) { return drafts.review(actor(principal),id); }
    @PostMapping("/releases") @ResponseStatus(HttpStatus.CREATED)
    public Release release(Principal principal,@RequestBody ReleaseInput input,@RequestHeader("Idempotency-Key") String key) { return releases.create(actor(principal),input,key); }
    @GetMapping("/releases") public List<Release> releases(Principal principal,@RequestParam(defaultValue="50") int limit) { return releases.releases(actor(principal),limit); }
    @GetMapping("/releases/{id}") public Release release(Principal principal,@PathVariable UUID id) { return releases.get(actor(principal),id); }
    @PostMapping("/releases/{id}/publish") public Release publish(Principal principal,@PathVariable UUID id,@RequestBody PublishInput input,@RequestHeader("Idempotency-Key") String key) { return releases.publish(actor(principal),id,input,key); }
    @PostMapping("/releases/{id}/activate") public Release activate(Principal principal,@PathVariable UUID id,@RequestBody PublishInput input,@RequestHeader("Idempotency-Key") String key) { return releases.activateHistorical(actor(principal),id,input,key); }
}
