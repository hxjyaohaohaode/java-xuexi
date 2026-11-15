package io.typeforge.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class RequestMetricsFilter extends OncePerRequestFilter {
    private final RequestMetrics metrics;
    public RequestMetricsFilter(RequestMetrics metrics) {this.metrics=metrics;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {return !request.getRequestURI().startsWith("/api/v1/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String trace=UUID.randomUUID().toString();request.setAttribute("typeforge.traceId",trace);response.setHeader("X-Request-ID",trace);
        metrics.begin();long begin=System.nanoTime();boolean failed=false;
        try {chain.doFilter(request,response);}
        catch(IOException | ServletException | RuntimeException error) {failed=true;throw error;}
        finally {metrics.finish(System.nanoTime()-begin,failed?500:response.getStatus());}
    }
}
