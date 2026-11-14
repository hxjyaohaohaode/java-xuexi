package io.typeforge.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.shared.SchemaCatalog;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ArtifactExportController {
    private final ArtifactExportService exports;
    private final IdentityService users;
    private final SchemaCatalog schemas;
    public ArtifactExportController(ArtifactExportService exports,IdentityService users,SchemaCatalog schemas) {this.exports=exports;this.users=users;this.schemas=schemas;}
    @PostMapping("/artifacts/{id}/exports") @ResponseStatus(HttpStatus.ACCEPTED)
    public ArtifactExportService.Job create(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {schemas.validate("ExportInput",input);return exports.create(users.session(principal.getName()).userId(),id,input.path("artifactRevision").asInt(),input.path("format").asText(),key);}
    @GetMapping("/export-jobs/{id}") public ArtifactExportService.Job get(Principal principal,@PathVariable UUID id) {return exports.get(users.session(principal.getName()).userId(),id);}
    @PostMapping("/export-jobs/{id}/cancel") public ArtifactExportService.Job cancel(Principal principal,@PathVariable UUID id) {return exports.cancel(users.session(principal.getName()).userId(),id);}
    @GetMapping("/export-jobs/{id}/download") public ResponseEntity<byte[]> download(Principal principal,@PathVariable UUID id) {
        var file=exports.download(users.session(principal.getName()).userId(),id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE,file.mimeType()+"; charset=UTF-8").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.filename()+"\"")
            .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("Content-Security-Policy","sandbox; default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'").header("X-Content-Type-Options","nosniff").eTag(file.contentHash()).body(file.bytes());
    }
}
