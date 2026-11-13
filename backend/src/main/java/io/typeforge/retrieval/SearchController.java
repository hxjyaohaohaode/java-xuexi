package io.typeforge.retrieval;

import io.typeforge.identity.IdentityService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SearchController {
    private final LocalKnowledgeSearch search;
    private final IdentityService users;
    public SearchController(LocalKnowledgeSearch search,IdentityService users) {this.search=search;this.users=users;}
    @GetMapping("/knowledge/search")
    public LocalKnowledgeSearch.SearchResult search(Principal principal,@RequestParam UUID releaseId,@RequestParam String q,@RequestParam(required=false) UUID conceptId,@RequestParam(defaultValue="5") int topK) {
        users.session(principal.getName());return search.search(releaseId,q,conceptId,topK);
    }
}
