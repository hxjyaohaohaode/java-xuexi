package io.typeforge.identity;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/preferences")
public class PreferenceController {
    private final IdentityService users;
    private final PreferenceService preferences;
    private final SchemaCatalog schemas;
    private final JsonCodec json;
    public PreferenceController(IdentityService users,PreferenceService preferences,SchemaCatalog schemas,JsonCodec json) {this.users=users;this.preferences=preferences;this.schemas=schemas;this.json=json;}
    @GetMapping public ResponseEntity<PreferenceService.Preference> get(Principal principal) {return response(preferences.get(users.session(principal.getName()).userId()));}
    @PutMapping public ResponseEntity<PreferenceService.Preference> update(Principal principal,@RequestBody JsonNode input,@RequestHeader("If-Match") String match) {
        schemas.validate("Preference",input);
        if(!match.matches("\"[1-9][0-9]{0,9}\"")) throw DomainException.invalid("If-Match must contain the quoted numeric revision");
        long revision=Long.parseLong(match.substring(1,match.length()-1));
        if(revision>Integer.MAX_VALUE) throw DomainException.invalid("Preference revision exceeds int32");
        return response(preferences.update(users.session(principal.getName()).userId(),json.read(input.toString(),PreferenceService.Preference.class),(int)revision));
    }
    private static ResponseEntity<PreferenceService.Preference> response(PreferenceService.Preference value) {return ResponseEntity.ok().eTag(Integer.toString(value.revision())).body(value);}
}
