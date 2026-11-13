package io.typeforge.operations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class ScaffoldSecurity {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // CSRF remains enabled. Business endpoints do not exist yet and are denied by default.
        return http.authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/framework").permitAll()
            .anyRequest().denyAll()).exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> response.sendError(403))
                .accessDeniedHandler((request, response, exception) -> response.sendError(403)))
            .build();
    }
}
