package io.typeforge.operations;

import io.typeforge.shared.DomainException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Aggregate authoritative facts only. Reading/activity counts never become mastery or invented time-on-task. */
@Service
public class LearningAnalyticsService {
    public record Totals(long workspaces,long gradedAttempts,long correctAttempts,long independentFamilies,long pendingReviews,long artifacts,long completedReadings,long labExperiments,long pendingProjectionEvents) {}
    public record Day(LocalDate date,long gradedAttempts,long correctAttempts,long completedReadings,long labExperiments,long activityEvents) {}
    public record Period(long gradedAttempts,long correctAttempts,Double correctRatePercent,long activeDays) {}
    public record Comparison(Period current,Period previous,boolean includesPartialToday) {}
    public record Analytics(Instant generatedAt,String timezone,int days,String evidenceNote,Totals totals,List<Day> daily,Comparison comparison) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public LearningAnalyticsService(JdbcTemplate jdbc,Clock clock) {this.jdbc=jdbc;this.clock=clock;}
    public Analytics get(UUID owner,int days) {
        if(days<7 || days>90) throw DomainException.invalid("Analytics window must be 7..90 days");
        if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND status='ACTIVE'",owner.toString()).isEmpty()) throw DomainException.missing();
        String id=owner.toString();LocalDate today=clock.instant().atZone(ZoneOffset.UTC).toLocalDate();LocalDate start=today.minusDays(days-1L),prior=start.minusDays(days);
        var series=new LinkedHashMap<LocalDate,long[]>();
        for(LocalDate day=prior;!day.isAfter(today);day=day.plusDays(1)) series.put(day,new long[5]);
        queryDays("exercise_attempt","created_at","user_id=? AND status='GRADED'","COUNT(*) AS attempts,SUM(CASE WHEN score=maximum THEN 1 ELSE 0 END) AS correct",id,prior,today,series,(row,rs)->{row[0]=rs.getLong("attempts");row[1]=rs.getLong("correct");});
        queryDays("learning_event","occurred_at","user_id=? AND event_type='READ_COMPLETED'","COUNT(*) AS amount",id,prior,today,series,(row,rs)->row[2]=rs.getLong("amount"));
        queryDays("lab_session","created_at","user_id=?","COUNT(*) AS amount",id,prior,today,series,(row,rs)->row[3]=rs.getLong("amount"));
        queryDays("workspace_event e JOIN learning_workspace w ON w.id=e.workspace_id","e.created_at","w.owner_id=?","COUNT(*) AS amount",id,prior,today,series,(row,rs)->row[4]=rs.getLong("amount"));
        Totals totals=new Totals(
            count("SELECT COUNT(*) FROM learning_workspace WHERE owner_id=?",id),
            count("SELECT COUNT(*) FROM exercise_attempt WHERE user_id=? AND status='GRADED'",id),
            count("SELECT COUNT(*) FROM exercise_attempt WHERE user_id=? AND status='GRADED' AND score=maximum",id),
            count("SELECT COUNT(DISTINCT family_id) FROM learning_family_evidence WHERE user_id=?",id),
            count("SELECT COUNT(*) FROM review_item WHERE user_id=? AND status='PENDING'",id),
            count("SELECT COUNT(*) FROM learning_artifact a JOIN learning_workspace w ON w.id=a.workspace_id WHERE w.owner_id=?",id),
            count("SELECT COUNT(*) FROM learning_event WHERE user_id=? AND event_type='READ_COMPLETED'",id),
            count("SELECT COUNT(*) FROM lab_session WHERE user_id=?",id),
            count("SELECT COUNT(*) FROM outbox_event e JOIN learning_workspace w ON w.id=e.aggregate_id WHERE w.owner_id=? AND e.published_at IS NULL",id));
        var daily=new ArrayList<Day>();series.forEach((date,value)->{if(!date.isBefore(start)) daily.add(new Day(date,value[0],value[1],value[2],value[3],value[4]));});
        return new Analytics(clock.instant(),"UTC",days,"统计来自本人服务端记录；正确率包含重复和辅助练习，不代表独立掌握度；不推测学习时长。",totals,List.copyOf(daily),new Comparison(period(series,start,today.plusDays(1)),period(series,prior,start),true));
    }
    @FunctionalInterface private interface DayFacts { void read(long[] values,java.sql.ResultSet row) throws java.sql.SQLException; }
    /** UTC boundaries travel through the same JDBC timestamp conversion as writes. SQL DATE(column) would silently use the server/JVM local calendar. */
    private void queryDays(String fromSql,String timeColumn,String filter,String aggregates,String owner,LocalDate from,LocalDate today,Map<LocalDate,long[]> series,DayFacts facts) {
        var arguments=new ArrayList<Object>();var bucket=new StringBuilder("CASE");int index=0;
        for(LocalDate boundary=from.plusDays(1);!boundary.isAfter(today);boundary=boundary.plusDays(1)) {
            bucket.append(" WHEN ").append(timeColumn).append("<? THEN ").append(index++);arguments.add(Timestamp.from(boundary.atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        bucket.append(" ELSE ").append(index).append(" END");arguments.add(owner);arguments.add(Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));arguments.add(Timestamp.from(today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        String sql="SELECT "+bucket+" AS day_index,"+aggregates+" FROM "+fromSql+" WHERE "+filter+" AND "+timeColumn+">=? AND "+timeColumn+"<? GROUP BY day_index";
        jdbc.query(sql,rs->{var values=series.get(from.plusDays(rs.getInt("day_index")));if(values!=null) facts.read(values,rs);},arguments.toArray());
    }
    private long count(String sql,String owner) {Long value=jdbc.queryForObject(sql,Long.class,owner);return value==null?0:value;}
    private static Period period(Map<LocalDate,long[]> series,LocalDate from,LocalDate until) {
        long attempts=0,correct=0,active=0;
        for(var entry:series.entrySet()) if(!entry.getKey().isBefore(from) && entry.getKey().isBefore(until)) {
            var row=entry.getValue();attempts+=row[0];correct+=row[1];if(row[0]+row[2]+row[3]+row[4]>0) active++;
        }
        return new Period(attempts,correct,attempts==0?null:Math.round(correct*10000d/attempts)/100d,active);
    }
}
