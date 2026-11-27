package io.typeforge.tutor;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server configuration only. No request may choose an endpoint, model, credential or quota. */
@Component
public final class TutorSettings {
    static final int MAX_PROMPT_BYTES=65_536;
    static final int MAX_RESPONSE_BYTES=131_072;
    private final int contextWindow, hintTokens, planTokens, explanationTokens, reviewTokens;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int timeoutMillis;
    private final int maxOutputTokens;
    private final long dailyTokenLimit;
    private final int dailyRequestLimit;
    private final int maxPending;
    private final boolean workerEnabled;
    private final boolean workflowStructuredOutputs;

    public TutorSettings(String endpoint,String apiKey,String model,int timeoutMillis,int maxOutputTokens,long dailyTokenLimit,
                         int dailyRequestLimit,int maxPending,boolean workerEnabled) {
        this(endpoint,apiKey,model,timeoutMillis,maxOutputTokens,dailyTokenLimit,dailyRequestLimit,maxPending,workerEnabled,true);
    }
    public TutorSettings(String endpoint,String apiKey,String model,int timeoutMillis,int maxOutputTokens,long dailyTokenLimit,
                         int dailyRequestLimit,int maxPending,boolean workerEnabled,boolean structured) {
        this(endpoint,apiKey,model,timeoutMillis,maxOutputTokens,dailyTokenLimit,dailyRequestLimit,maxPending,workerEnabled,
            structured,32768,768,1536,6144,8192);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TutorSettings(@Value("${typeforge.tutor.provider-url:}") String endpoint,
                         @Value("${typeforge.tutor.api-key:}") String apiKey,
                         @Value("${typeforge.tutor.model:}") String model,
                         @Value("${typeforge.tutor.timeout-millis:120000}") int timeoutMillis,
                         @Value("${typeforge.tutor.max-output-tokens:8192}") int maxOutputTokens,
                         @Value("${typeforge.tutor.daily-token-limit:1000000}") long dailyTokenLimit,
                         @Value("${typeforge.tutor.daily-request-limit:100}") int dailyRequestLimit,
                         @Value("${typeforge.tutor.max-pending:4}") int maxPending,
                         @Value("${typeforge.tutor.worker-enabled:true}") boolean workerEnabled,
                         @Value("${typeforge.tutor.workflow-structured-outputs:true}") boolean workflowStructuredOutputs,
                         @Value("${typeforge.tutor.context-window-tokens:32768}") int contextWindow,
                         @Value("${typeforge.tutor.hint-output-tokens:768}") int hintTokens,
                         @Value("${typeforge.tutor.plan-output-tokens:1536}") int planTokens,
                         @Value("${typeforge.tutor.explanation-output-tokens:6144}") int explanationTokens,
                         @Value("${typeforge.tutor.review-output-tokens:8192}") int reviewTokens) {
        if(contextWindow<2048 || contextWindow>1_048_576
            || java.util.stream.IntStream.of(hintTokens,planTokens,explanationTokens,reviewTokens).anyMatch(n->n<64 || n>16384))
            throw new IllegalArgumentException("Invalid tutor role or context-window limit");
        this.contextWindow=contextWindow;this.hintTokens=hintTokens;this.planTokens=planTokens;
        this.explanationTokens=explanationTokens;this.reviewTokens=reviewTokens;
        this.workflowStructuredOutputs=workflowStructuredOutputs;
        this.endpoint=parseEndpoint(endpoint);
        this.apiKey=apiKey==null?"":apiKey;
        this.model=model==null?"":model;
        if(this.endpoint!=null && (this.endpoint.getHost()==null || this.endpoint.getUserInfo()!=null || this.endpoint.getQuery()!=null || this.endpoint.getFragment()!=null
            || !this.endpoint.getPath().endsWith("/chat/completions")
            || !("https".equals(this.endpoint.getScheme()) || "http".equals(this.endpoint.getScheme()) && java.util.Set.of("localhost","127.0.0.1","[::1]","::1").contains(this.endpoint.getHost())))) {
            throw new IllegalArgumentException("Tutor provider requires HTTPS or loopback HTTP and a /chat/completions path");
        }
        if(this.apiKey.length()>4096 || this.apiKey.chars().anyMatch(c->c<32 || c==127) || this.model.length()>128 || this.model.chars().anyMatch(c->c<32 || c==127)
            || timeoutMillis<100 || timeoutMillis>300000 || maxOutputTokens<64 || maxOutputTokens>16384
            || dailyTokenLimit<1 || dailyTokenLimit>1_000_000_000L || dailyRequestLimit<1 || dailyRequestLimit>10000 || maxPending<1 || maxPending>16) {
            throw new IllegalArgumentException("Invalid bounded tutor configuration");
        }
        this.timeoutMillis=timeoutMillis;this.maxOutputTokens=maxOutputTokens;this.dailyTokenLimit=dailyTokenLimit;
        this.dailyRequestLimit=dailyRequestLimit;this.maxPending=maxPending;this.workerEnabled=workerEnabled;
    }
    URI endpoint() { return endpoint; }
    String apiKey() { return apiKey; }
    public String model() { return model; }
    public int timeoutMillis() { return timeoutMillis; }
    public int maxOutputTokens() { return maxOutputTokens; }
    int contextWindow() { return contextWindow; }
    int outputLimit(TutorAnswerProvider.Prompt prompt) {
        int desired=switch(prompt.purpose()) {
            case PLANNER -> planTokens;
            case REVIEW -> reviewTokens;
            case CONCEPT,EXAMPLE,REPAIR -> explanationTokens;
            case TUTOR -> switch(prompt.hintLevel()) {
                case 0,1 -> hintTokens;
                case 2 -> Math.max(hintTokens,explanationTokens/2);
                default -> explanationTokens;
            };
        };
        return Math.min(maxOutputTokens,desired);
    }
    public long reservationTokens() {
        return Math.min(contextWindow,MAX_PROMPT_BYTES+1024L+maxOutputTokens);
    }
    public long dailyTokenLimit() { return dailyTokenLimit; }
    public int dailyRequestLimit() { return dailyRequestLimit; }
    public int maxPending() { return maxPending; }
    public boolean workerEnabled() { return workerEnabled; }
    public boolean workflowStructuredOutputs() { return workflowStructuredOutputs; }
    public boolean configured() { return endpoint!=null && !apiKey.isBlank() && !model.isBlank(); }
    private static URI parseEndpoint(String value) {
        if(value==null || value.isBlank()) return null;
        try { return URI.create(value); }
        catch(IllegalArgumentException ignored) { throw new IllegalArgumentException("Invalid tutor provider endpoint"); }
    }
}
