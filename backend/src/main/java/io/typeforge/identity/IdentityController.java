package io.typeforge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class IdentityController {
    public record Credentials(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=72) String password) {}
    private final IdentityService users;
    private final AuthenticationManager authentication;
    private final CsrfTokenRepository csrf;
    private final AccountService accounts;
    private final AuthenticationLimit limits;
    public IdentityController(IdentityService users,AuthenticationManager authentication,CsrfTokenRepository csrf,AccountService accounts,AuthenticationLimit limits) { this.users=users;this.authentication=authentication;this.csrf=csrf;this.accounts=accounts;this.limits=limits; }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.Session register(@Valid @RequestBody Credentials input,HttpServletRequest request) { limits.register(request.getRemoteAddr());return users.register(input.username(),input.password()); }
    @PostMapping("/session")
    public IdentityService.Session login(@Valid @RequestBody Credentials input,HttpServletRequest request,HttpServletResponse response) {
        limits.login(request.getRemoteAddr(),input.username());
        var result=authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username(),input.password()));
        if(request.getSession(false)!=null) accounts.revokeSessionId(request.getSession(false).getId());
        if (request.getSession(false)!=null) request.changeSessionId();
        accounts.openSession((AccountPrincipal)result.getPrincipal(),request.getSession(true).getId(),request.getHeader("User-Agent"));
        var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,request,response);
        csrf.saveToken(null,request,response);
        return users.session(result.getName());
    }
    @GetMapping("/session") public IdentityService.Session current(Principal principal) { return users.session(principal.getName()); }
    @DeleteMapping("/session") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request,HttpServletResponse response) {
        if(request.getSession(false)!=null) accounts.revokeSessionId(request.getSession(false).getId());
        if(request.getSession(false)!=null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext(); csrf.saveToken(null,request,response);
    }
}
