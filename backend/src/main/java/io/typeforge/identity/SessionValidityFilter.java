package io.typeforge.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every accepted session is checked against persisted revocations; no cookie or caller-supplied identity is trusted. */
public final class SessionValidityFilter extends OncePerRequestFilter {
    private final AccountService accounts;
    public SessionValidityFilter(AccountService accounts) { this.accounts=accounts; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.equals("/api/v1/health") || path.equals("/api/v1/framework") || path.equals("/api/v1/auth/csrf")
            || path.equals("/api/v1/auth/register") || (path.equals("/api/v1/auth/session") && !request.getMethod().equals("GET"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            boolean valid;
            try { valid=authentication.getPrincipal() instanceof AccountPrincipal account && accounts.validSession(account,request.getSession(false)==null?null:request.getSession(false).getId()); }
            catch(DataAccessException failure) {
                response.setStatus(503);response.setContentType("application/problem+json");response.setHeader("Cache-Control","no-store");
                response.getWriter().write("{\"status\":503,\"code\":\"SESSION_CHECK_UNAVAILABLE\",\"detail\":\"Session validation is temporarily unavailable; your changes were not accepted\"}");return;
            }
            if(!valid) {
                if(request.getSession(false)!=null) request.getSession(false).invalidate();
                SecurityContextHolder.clearContext();response.setStatus(401);response.setContentType("application/problem+json");response.setHeader("Cache-Control","no-store");
                response.getWriter().write("{\"status\":401,\"code\":\"SESSION_REVOKED\",\"detail\":\"This session expired or was revoked; sign in again\"}");return;
            }
        }
        chain.doFilter(request,response);
    }
}
