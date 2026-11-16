package io.typeforge.operations;

import io.typeforge.identity.IdentityService;
import io.typeforge.shared.DomainException;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
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
        var now=clock.instant();var queues=new LinkedHashMap<String,Long>();
        queues.put("pendingEvents",count("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL"));
        queues.put("deadLetters",count("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL AND attempts>=5"));
        queues.put("pendingTutor",count("SELECT COUNT(*) FROM tutor_request WHERE status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING')"));
        queues.put("pendingExports",count("SELECT COUNT(*) FROM export_job WHERE status IN ('QUEUED','RUNNING')"));
        queues.put("pendingCompilation",count("SELECT COUNT(*) FROM compile_job WHERE status IN ('QUEUED','RUNNING')"));
        queues.put("pendingEvaluations",count("SELECT COUNT(*) FROM evaluation_run WHERE status IN ('QUEUED','RUNNING')"));
        queues.put("failedEvaluations",count("SELECT COUNT(*) FROM evaluation_run WHERE status='FAILED'"));
        queues.put("activeImportPreviews",count("SELECT COUNT(*) FROM import_batch WHERE status='PREVIEW' AND expires_at>?",Timestamp.from(now)));
        queues.put("expiredImportPreviews",count("SELECT COUNT(*) FROM import_batch WHERE status='PREVIEW' AND expires_at<=?",Timestamp.from(now)));
        // Measure retained serialized payloads directly, including cancelled partial evidence and overdue cleanup residue.
        long definitions=count("SELECT COALESCE(SUM(OCTET_LENGTH(snapshot_json)+OCTET_LENGTH(corpus_json)),0) FROM evaluation_suite_revision");
        long reports=count("SELECT COALESCE(SUM(OCTET_LENGTH(report_json)),0) FROM evaluation_run");
        long cases=count("SELECT COALESCE(SUM(OCTET_LENGTH(result_json)),0) FROM evaluation_case_result");
        queues.put("storedEvaluationBytes",definitions+reports+cases);
        queues.put("importPreviewBytes",count("SELECT COALESCE(SUM(OCTET_LENGTH(original_markdown)+OCTET_LENGTH(cleaned_markdown)),0) FROM import_batch_item"));
        return Map.of("generatedAt",now,"requestMetrics",metrics.snapshot(),"queues",Map.copyOf(queues),"measurementScope","Aggregate counters are current reads, not an atomic cross-queue snapshot. Bytes measure retained serialized database payloads, not physical database allocation. HTTP histogram covers this process lifetime; percentile upper bounds are not production capacity guarantees.");
    }
    private long count(String sql,Object... parameters) {Long value=jdbc.queryForObject(sql,Long.class,parameters);return value==null?0:value;}
}
