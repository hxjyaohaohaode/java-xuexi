package io.typeforge.ingestion;

import io.typeforge.identity.IdentityService;
import io.typeforge.ingestion.IngestionModels.Capture;
import io.typeforge.ingestion.IngestionModels.CaptureInput;
import io.typeforge.ingestion.IngestionModels.Source;
import io.typeforge.ingestion.IngestionModels.SourceInput;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public final class IngestionController {
    private final ManualIngestionService ingestion;
    private final IdentityService identities;
    public IngestionController(ManualIngestionService ingestion,IdentityService identities) { this.ingestion=ingestion;this.identities=identities; }
    private UUID actor(Principal principal) { return identities.session(principal.getName()).userId(); }
    @GetMapping("/sources") public List<Source> sources(Principal principal,@RequestParam(defaultValue="50") int limit) { return ingestion.sources(actor(principal),limit); }
    @PostMapping("/sources") @ResponseStatus(HttpStatus.CREATED)
    public Source create(Principal principal,@RequestBody SourceInput input,@RequestHeader("Idempotency-Key") String key) { return ingestion.register(actor(principal),input,key); }
    @GetMapping("/sources/{id}") public Source source(Principal principal,@PathVariable UUID id) { return ingestion.source(actor(principal),id); }
    @PostMapping("/sources/{id}/imports") @ResponseStatus(HttpStatus.CREATED)
    public Capture capture(Principal principal,@PathVariable UUID id,@RequestBody CaptureInput input,@RequestHeader("Idempotency-Key") String key) { return ingestion.capture(actor(principal),id,input,key); }
    @GetMapping("/sources/{id}/imports") public List<Capture> captures(Principal principal,@PathVariable UUID id,@RequestParam(defaultValue="50") int limit) { return ingestion.captures(actor(principal),id,limit); }
    @GetMapping("/imports/{id}") public Capture capture(Principal principal,@PathVariable UUID id) { return ingestion.capture(actor(principal),id); }
}
