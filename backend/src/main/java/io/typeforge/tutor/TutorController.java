package io.typeforge.tutor;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.IdentityService;
import io.typeforge.shared.SchemaCatalog;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Session/CSRF protected public surface. Credentials and worker leases never enter client DTOs. */
@RestController
@RequestMapping("/api/v1/tutor")
public final class TutorController {
    private final TutorService tutors;
    private final TutorDocumentService documents;
    private final IdentityService users;
    private final SchemaCatalog schemas;
    public TutorController(TutorService tutors,TutorDocumentService documents,IdentityService users,SchemaCatalog schemas) { this.tutors=tutors;this.documents=documents;this.users=users;this.schemas=schemas; }
    private UUID owner(Principal principal) { return users.session(principal.getName()).userId(); }
    @PostMapping("/conversations") @ResponseStatus(HttpStatus.CREATED)
    public TutorService.ConversationSummary createConversation(Principal principal,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("ConversationInput",input);
        return tutors.createConversation(owner(principal),new TutorService.ConversationInput(UUID.fromString(input.path("workspaceId").asText()),UUID.fromString(input.path("releaseId").asText()),input.path("title").asText()),key);
    }
    @GetMapping("/conversations")
    public TutorService.ConversationList conversations(Principal principal,@RequestParam(required=false) UUID workspaceId,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="20") int limit) {
        return tutors.conversations(owner(principal),workspaceId,cursor,limit);
    }
    @GetMapping("/conversations/{id}")
    public TutorService.ConversationView conversation(Principal principal,@PathVariable UUID id,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="20") int limit) {
        return tutors.conversation(owner(principal),id,cursor,limit);
    }
    @PostMapping("/requests") @ResponseStatus(HttpStatus.ACCEPTED)
    public TutorService.TutorResult request(Principal principal,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("TutorInput",input);
        UUID attempt=input.hasNonNull("attemptId")?UUID.fromString(input.path("attemptId").asText()):null;
        return tutors.submit(owner(principal),new TutorService.TutorInput(UUID.fromString(input.path("conversationId").asText()),UUID.fromString(input.path("snapshotId").asText()),TutorService.Mode.valueOf(input.path("mode").asText()),input.path("question").asText(),input.path("hintLevel").asInt(),attempt),key);
    }
    @GetMapping("/requests/{id}") public TutorService.TutorResult get(Principal principal,@PathVariable UUID id) { return tutors.get(owner(principal),id); }
    @PostMapping("/requests/{id}/cancel")
    public TutorService.TutorResult cancel(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input) {
        schemas.validate("TutorCancelInput",input);
        return tutors.cancel(owner(principal),id,input.path("expectedEpoch").asLong());
    }
    @PostMapping("/requests/{id}/document") @ResponseStatus(HttpStatus.CREATED)
    public TutorDocumentService.DocumentResult document(Principal principal,@PathVariable UUID id,@RequestBody JsonNode input,@RequestHeader("Idempotency-Key") String key) {
        schemas.validate("TutorDocumentInput",input);
        return documents.create(owner(principal),id,input.path("title").asText(),key);
    }
}
