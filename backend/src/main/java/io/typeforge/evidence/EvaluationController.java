package io.typeforge.evidence;

import static io.typeforge.evidence.EvaluationModels.*;

import io.typeforge.identity.IdentityService;
import io.typeforge.shared.Page;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class EvaluationController {
    private final IdentityService identities;
    private final EvaluationService service;
    public EvaluationController(IdentityService identities,EvaluationService service){this.identities=identities;this.service=service;}
    private UUID owner(Principal principal){return identities.session(principal.getName()).userId();}
    @GetMapping("/workspaces/{workspaceId}/evaluation-suites")
    public Page<SuiteSummary> suites(Principal principal,@PathVariable UUID workspaceId,@RequestParam(defaultValue="30") int limit,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="false") boolean includeArchived){return service.suites(owner(principal),workspaceId,limit,cursor,includeArchived);}
    @PostMapping("/workspaces/{workspaceId}/evaluation-suites") @ResponseStatus(HttpStatus.CREATED)
    public Suite create(Principal principal,@PathVariable UUID workspaceId,@RequestBody SuiteInput input,@RequestHeader("Idempotency-Key") String key){return service.create(owner(principal),workspaceId,input,key);}
    @GetMapping("/evaluation-suites/{id}")
    public Suite get(Principal principal,@PathVariable UUID id){return service.get(owner(principal),id);}
    @GetMapping("/evaluation-suites/{id}/revisions/{revision}")
    public Suite revision(Principal principal,@PathVariable UUID id,@PathVariable int revision){return service.revision(owner(principal),id,revision);}
    @PostMapping("/evaluation-suites/{id}/revisions") @ResponseStatus(HttpStatus.CREATED)
    public Suite revise(Principal principal,@PathVariable UUID id,@RequestBody RevisionInput input,@RequestHeader("Idempotency-Key") String key){return service.revise(owner(principal),id,input,key);}
    @PostMapping("/evaluation-suites/{id}/archive")
    public Suite archive(Principal principal,@PathVariable UUID id,@RequestBody ArchiveInput input){return service.archive(owner(principal),id,input.expectedRevision());}
    @GetMapping("/evaluation-suites/{id}/runs")
    public Page<Run> runs(Principal principal,@PathVariable UUID id,@RequestParam(defaultValue="30") int limit,@RequestParam(required=false) String cursor){return service.runs(owner(principal),id,limit,cursor);}
    @PostMapping("/evaluation-suites/{id}/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    public Run createRun(Principal principal,@PathVariable UUID id,@RequestBody RunInput input,@RequestHeader("Idempotency-Key") String key){return service.createRun(owner(principal),id,input,key);}
    @GetMapping("/evaluation-runs/{id}")
    public Run run(Principal principal,@PathVariable UUID id){return service.run(owner(principal),id);}
    @PostMapping("/evaluation-runs/{id}/cancel")
    public Run cancel(Principal principal,@PathVariable UUID id){return service.cancel(owner(principal),id);}
    @GetMapping("/evaluation-runs/{id}/report")
    public ResponseEntity<byte[]> report(Principal principal,@PathVariable UUID id){var file=service.report(owner(principal),id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
        .header("Content-Disposition","attachment; filename=\""+file.filename()+"\"")
        .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff")
        .header("Content-Security-Policy","default-src 'none'; sandbox")
        .header("X-Content-SHA256",file.contentHash()).contentLength(file.bytes().length).body(file.bytes());}
}
