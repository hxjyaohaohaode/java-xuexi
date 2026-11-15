package io.typeforge.identity;

import io.typeforge.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class AccountController {
    public record ProfileInput(@NotBlank @Size(max=80) String displayName,int revision) {}
    public record PasswordInput(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Size(max=72) String newPassword) {}
    public record ClosureInput(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Size(max=64) String confirmationUsername) {}
    private final IdentityService users;
    private final AccountService accounts;
    private final AccountClosureService closure;
    private final AuthenticationLimit limits;
    private final CsrfTokenRepository csrf;
    public AccountController(IdentityService users,AccountService accounts,AccountClosureService closure,AuthenticationLimit limits,CsrfTokenRepository csrf) { this.users=users;this.accounts=accounts;this.closure=closure;this.limits=limits;this.csrf=csrf; }
    @GetMapping("/profile") public ResponseEntity<AccountService.Profile> profile(Principal actor) { return response(accounts.profile(owner(actor))); }
    @PutMapping("/profile") public ResponseEntity<AccountService.Profile> update(Principal actor,@RequestHeader("If-Match") String match,@Valid @RequestBody ProfileInput input) {
        if(!match.equals("\""+input.revision()+"\"")) throw DomainException.invalid("If-Match must equal the quoted profile revision");
        return response(accounts.update(owner(actor),input.displayName(),input.revision()));
    }
    @PutMapping("/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(Principal actor,@Valid @RequestBody PasswordInput input,HttpServletRequest request,HttpServletResponse response) {
        UUID owner=owner(actor);limits.sensitive(owner);accounts.changePassword(owner,input.currentPassword(),input.newPassword());clear(request,response);
    }
    @GetMapping("/sessions") public Map<String,Object> sessions(Principal actor,HttpServletRequest request) { return Map.of("items",accounts.sessions(owner(actor),sessionId(request))); }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Principal actor,@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response) { if(accounts.revoke(owner(actor),id,sessionId(request))) clear(request,response); }
    @DeleteMapping("/sessions") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void others(Principal actor,HttpServletRequest request) { accounts.revokeOthers(owner(actor),sessionId(request)); }
    @PostMapping("/close-account")
    public AccountClosureService.Closure close(Principal actor,@Valid @RequestBody ClosureInput input,HttpServletRequest request,HttpServletResponse response) {
        UUID owner=owner(actor);limits.sensitive(owner);var result=closure.close(owner,input.currentPassword(),input.confirmationUsername());clear(request,response);return result;
    }
    private UUID owner(Principal actor) { return users.session(actor.getName()).userId(); }
    private static String sessionId(HttpServletRequest request) { return request.getSession(false)==null?null:request.getSession(false).getId(); }
    private void clear(HttpServletRequest request,HttpServletResponse response) { if(request.getSession(false)!=null) request.getSession(false).invalidate();SecurityContextHolder.clearContext();csrf.saveToken(null,request,response); }
    private static ResponseEntity<AccountService.Profile> response(AccountService.Profile profile) { return ResponseEntity.ok().eTag(Integer.toString(profile.revision())).body(profile); }
}
