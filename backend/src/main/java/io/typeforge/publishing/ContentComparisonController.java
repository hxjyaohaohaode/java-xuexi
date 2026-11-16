package io.typeforge.publishing;

import io.typeforge.identity.IdentityService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ContentComparisonController {
    private final IdentityService identities;
    private final ContentComparisonService comparisons;
    public ContentComparisonController(IdentityService identities,ContentComparisonService comparisons) {this.identities=identities;this.comparisons=comparisons;}
    @GetMapping("/api/v1/admin/content-diffs")
    public ContentComparisonService.Comparison compare(Principal principal,@RequestParam String kind,@RequestParam UUID id,@RequestParam int fromRevision,@RequestParam int toRevision) {
        return comparisons.compare(identities.session(principal.getName()).userId(),kind,id,fromRevision,toRevision);
    }
}
