package io.typeforge.compiler;

import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operator-owned endpoint. Learner requests cannot select a host, image, compiler or flags. */
@Component
public final class CompileSettings {
    public static final String PROTOCOL="typeforge-compile-v1";
    public static final int MAX_SOURCE_BYTES=32768;
    private final URI endpoint;
    private final String token;
    private final boolean enabled;
    private final boolean dispatchEnabled;
    private final boolean executionEnabled;
    public boolean executionEnabled() { return configured() && executionEnabled; }
    private final int timeoutMillis;
    private final int maxPending;
    private final int globalPending;
    private final int dailyLimit;
    public CompileSettings(String url,String token,boolean enabled,boolean dispatchEnabled,int timeoutMillis,int maxPending,int globalPending,int dailyLimit) {
        this(url,token,enabled,dispatchEnabled,timeoutMillis,maxPending,globalPending,dailyLimit,false);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CompileSettings(@Value("${typeforge.compiler.worker-url:}") String url,
                           @Value("${typeforge.compiler.worker-token:}") String token,
                           @Value("${typeforge.compiler.enabled:false}") boolean enabled,
                           @Value("${typeforge.compiler.dispatch-enabled:true}") boolean dispatchEnabled,
                           @Value("${typeforge.compiler.timeout-millis:45000}") int timeoutMillis,
                           @Value("${typeforge.compiler.max-pending:4}") int maxPending,
                           @Value("${typeforge.compiler.global-pending:256}") int globalPending,
                           @Value("${typeforge.compiler.daily-limit:200}") int dailyLimit,
                           @Value("${typeforge.compiler.execution-enabled:false}") boolean executionEnabled) {
        this.executionEnabled=executionEnabled;
        this.endpoint=url==null || url.isBlank()?null:URI.create(url);
        this.token=token==null?"":token;
        this.enabled=enabled;this.dispatchEnabled=dispatchEnabled;
        if(endpoint!=null && (endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
            || !endpoint.getPath().equals("/v1/compile") || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()) && Set.of("localhost","127.0.0.1","[::1]","::1").contains(endpoint.getHost())))) {
            throw new IllegalArgumentException("Compiler worker requires HTTPS or loopback HTTP and /v1/compile");
        }
        if(this.token.length()>4096 || this.token.chars().anyMatch(c->c<32 || c==127) || timeoutMillis<1000 || timeoutMillis>90000
            || maxPending<1 || maxPending>16 || globalPending<1 || globalPending>4096 || dailyLimit<1 || dailyLimit>10000) {
            throw new IllegalArgumentException("Invalid bounded compiler configuration");
        }
        if(enabled && (endpoint==null || this.token.length()<32)) throw new IllegalArgumentException("Enabled compiler requires an explicit worker URL and at least 32-character token");
        this.timeoutMillis=timeoutMillis;this.maxPending=maxPending;this.globalPending=globalPending;this.dailyLimit=dailyLimit;
    }
    public boolean configured() {return enabled && endpoint!=null && token.length()>=32;}
    public boolean dispatchEnabled() {return dispatchEnabled;}
    URI endpoint(){return endpoint;}
    String token(){return token;}
    public int timeoutMillis(){return timeoutMillis;}
    public int maxPending(){return maxPending;}
    public int globalPending(){return globalPending;}
    public int dailyLimit(){return dailyLimit;}
}
