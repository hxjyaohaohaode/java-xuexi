package io.typeforge.ingestion;

import static io.typeforge.ingestion.BatchImportModels.*;

import io.typeforge.identity.IdentityService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/import-batches")
public final class BatchImportController {
    private final IdentityService identities;
    private final BatchImportService batches;
    public BatchImportController(IdentityService identities,BatchImportService batches) {this.identities=identities;this.batches=batches;}
    private UUID actor(Principal principal) {return identities.session(principal.getName()).userId();}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Batch preview(Principal principal,@RequestParam UUID sourceId,@RequestParam String processingNote,@RequestPart("files") List<MultipartFile> files,@RequestHeader("Idempotency-Key") String key) {return batches.preview(actor(principal),sourceId,processingNote,files,key);}
    @GetMapping public List<Summary> list(Principal principal,@RequestParam(defaultValue="20") int limit) {return batches.list(actor(principal),limit);}
    @GetMapping("/{id}") public Batch get(Principal principal,@PathVariable UUID id) {return batches.get(actor(principal),id);}
    @PostMapping("/{id}/commit") public Batch commit(Principal principal,@PathVariable UUID id,@RequestBody CommitInput input,@RequestHeader("Idempotency-Key") String key) {return batches.commit(actor(principal),id,input,key);}
    @PostMapping("/{id}/cancel") public Batch cancel(Principal principal,@PathVariable UUID id,@RequestBody CancelInput input,@RequestHeader("Idempotency-Key") String key) {return batches.cancel(actor(principal),id,input,key);}
}
