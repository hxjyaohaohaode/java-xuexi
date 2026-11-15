package io.typeforge.operations;

import io.typeforge.identity.IdentityService;
import io.typeforge.shared.DomainException;
import java.security.Principal;
import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OperationsController {
    private final IdentityService users;
    private final LearningAnalyticsService analytics;
    private final RequestMetrics metrics;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public OperationsController(IdentityService users,LearningAnalyticsService analytics,RequestMetrics metrics,JdbcTemplate jdbc,Clock clock) {this.users=users;this.analytics=analytics;this.metrics=metrics;this.jdbc=jdbc;this.clock=clock;}
    @GetMapping("/me/analytics") public LearningAnalyticsService.Analytics analytics(Principal principal,@RequestParam(defaultValue="30") int days) {return analytics.get(users.session(principal.getName()).userId(),days);}
    @GetMapping("/admin/operations") public Map<String,Object> operations(Principal principal) {
        if(!users.session(principal.getName()).roles().contains("ADMIN")) throw new DomainException("ADMIN_REQUIRED",403,"Current administrator role is required");
        var queues=Map.of("pendingEvents",count("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL"),"deadLetters",count("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL AND attempts>=5"),"pendingTutor",count("SELECT COUNT(*) FROM tutor_request WHERE status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING')"),"pendingExports",count("SELECT COUNT(*) FROM export_job WHERE status IN ('QUEUED','RUNNING')"),"pendingCompilation",count("SELECT COUNT(*) FROM compile_job WHERE status IN ('QUEUED','RUNNING')"));
        return Map.of("generatedAt",clock.instant(),"requestMetrics",metrics.snapshot(),"queues",queues,"measurementScope","HTTP histogram since this process started; percentile values are histogram upper bounds, not deployment capacity guarantees");
    }
    private long count(String sql) {Long value=jdbc.queryForObject(sql,Long.class);return value==null?0:value;}
}
