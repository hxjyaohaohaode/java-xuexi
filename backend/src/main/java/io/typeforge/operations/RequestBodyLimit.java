package io.typeforge.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds ordinary and chunked JSON before Jackson materializes its tree; multipart limits do not do this. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class RequestBodyLimit extends OncePerRequestFilter {
    public static final int MAX_BYTES=262144;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String type=request.getContentType();
        return !request.getRequestURI().startsWith("/api/v1/") || !Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())
            || type==null || !type.toLowerCase(Locale.ROOT).split(";",2)[0].trim().endsWith("json");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getContentLengthLong()>MAX_BYTES) { reject(response);return; }
        byte[] body=request.getInputStream().readNBytes(MAX_BYTES+1);
        if(body.length>MAX_BYTES) { reject(response);return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() {return body.length;}
            @Override public long getContentLengthLong() {return body.length;}
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream stream=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() {return stream.read();}
                    @Override public int read(byte[] buffer,int offset,int length) {return stream.read(buffer,offset,length);}
                    @Override public boolean isFinished() {return stream.available()==0;}
                    @Override public boolean isReady() {return true;}
                    @Override public void setReadListener(ReadListener listener) {
                        if(listener==null) throw new IllegalArgumentException("Read listener required");
                        try {if(!isFinished()) listener.onDataAvailable();if(isFinished()) listener.onAllDataRead();}
                        catch(IOException failure) {listener.onError(failure);}
                    }
                };
            }
            @Override public BufferedReader getReader() {return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);response.setContentType("application/problem+json");response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        response.getWriter().write("{\"status\":413,\"code\":\"REQUEST_TOO_LARGE\",\"detail\":\"JSON request body exceeds 262144 bytes\"}");
    }
}
