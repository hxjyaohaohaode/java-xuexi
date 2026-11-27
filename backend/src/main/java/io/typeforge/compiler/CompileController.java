package io.typeforge.compiler;

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
public final class CompileController {
    private final CompileService compiler;
    private final IdentityService users;
    private final SchemaCatalog schemas;
    public CompileController(CompileService compiler,IdentityService users,SchemaCatalog schemas){this.compiler=compiler;this.users=users;this.schemas=schemas;}
    private UUID owner(Principal principal){return users.session(principal.getName()).userId();}
    @PostMapping("/compile-jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    public CompileService.Job create(Principal principal,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key){
        schemas.validate("CompileInput",input);
        return compiler.create(owner(principal),UUID.fromString(input.path("workspaceId").asText()),UUID.fromString(input.path("releaseId").asText()),input.path("source").asText(),input.path("mode").asText(),input.path("language").asText("JAVA"),input.path("stdin").asText(""),key);
    }
    @GetMapping("/compile-jobs/{id}") public CompileService.Job get(Principal principal,@PathVariable UUID id){return compiler.get(owner(principal),id);}
    @GetMapping("/workspaces/{id}/compile-jobs") public List<CompileService.Job> list(Principal principal,@PathVariable UUID id){return compiler.list(owner(principal),id);}
    @PostMapping("/compile-jobs/{id}/cancel") public CompileService.Job cancel(Principal principal,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key){return compiler.cancel(owner(principal),id,key);}
}
