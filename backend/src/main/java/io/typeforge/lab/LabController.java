package io.typeforge.lab;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LabController {
    private final LabService labs;
    private final IdentityService users;
    public LabController(LabService labs,IdentityService users) {this.labs=labs;this.users=users;}
    @PostMapping("/labs/sessions") @ResponseStatus(HttpStatus.CREATED)
    public LabService.Session create(Principal principal,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {return labs.create(users.session(principal.getName()).userId(),input,key);}
    @GetMapping("/labs/sessions/{id}") public LabService.Session get(Principal principal,@PathVariable UUID id) {return labs.get(users.session(principal.getName()).userId(),id);}
    @GetMapping("/workspaces/{id}/labs") public List<LabService.Session> list(Principal principal,@PathVariable UUID id) {return labs.list(users.session(principal.getName()).userId(),id);}
}
