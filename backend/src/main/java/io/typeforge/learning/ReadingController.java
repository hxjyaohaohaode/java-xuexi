package io.typeforge.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReadingController {
    private final ReadingService readings;
    private final IdentityService users;
    public ReadingController(ReadingService readings,IdentityService users) {this.readings=readings;this.users=users;}
    @PostMapping("/learning-events") @ResponseStatus(HttpStatus.CREATED)
    public ReadingService.Receipt record(Principal principal,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {return readings.record(users.session(principal.getName()).userId(),input,key);}
    @GetMapping("/workspaces/{id}/readings")
    public Map<String,Object> history(Principal principal,@PathVariable UUID id) {return Map.of("items",readings.history(users.session(principal.getName()).userId(),id));}
}
