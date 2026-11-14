package io.typeforge.operations;

import io.typeforge.identity.IdentityService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class ScaffoldSecurity {
    @Bean AuthenticationManager authenticationManager(IdentityService users,PasswordEncoder passwords) {
        var provider=new DaoAuthenticationProvider(users); provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }
    @Bean CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SecurityFilterChain filterChain(HttpSecurity http,CsrfTokenRepository csrf) throws Exception {
        return http.csrf(config->config.csrfTokenRepository(csrf))
            .authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.GET,"/api/v1/health","/api/v1/framework","/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.GET,"/","/index.html","/assets/**","/favicon.svg").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/session").permitAll()
                .requestMatchers("/api/v1/internal/**").denyAll()
                .requestMatchers("/api/v1/admin/**").hasAnyRole("EDITOR","ADMIN")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((request,response,exception)->{
                    response.setStatus(401);response.setContentType("application/problem+json");
                    response.getWriter().write("{\"status\":401,\"code\":\"UNAUTHENTICATED\",\"detail\":\"Authentication is required\"}");
                })
                .accessDeniedHandler((request,response,exception)->{
                    response.setStatus(403);response.setContentType("application/problem+json");
                    boolean invalidCsrf=exception instanceof org.springframework.security.web.csrf.CsrfException;
                    response.getWriter().write(invalidCsrf?"{\"status\":403,\"code\":\"CSRF_INVALID\",\"detail\":\"Refresh the session CSRF token before retrying this command\"}":"{\"status\":403,\"code\":\"FORBIDDEN\",\"detail\":\"Request is not authorized\"}");
                }))
            .build();
    }
}
