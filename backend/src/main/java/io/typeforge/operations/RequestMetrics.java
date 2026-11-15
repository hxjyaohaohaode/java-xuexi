package io.typeforge.operations;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.stereotype.Component;

/** Fixed-size, process-local histogram. No URLs, account identifiers, request bodies or credentials are collected. */
@Component
public class RequestMetrics {
    private static final long[] BOUNDS={1,5,10,25,50,100,250,500,1000,2500,5000,10000,30000,60000,Long.MAX_VALUE};
    public record Snapshot(Instant since,long completed,long inFlight,long serverErrors,long rateLimited,Long p50UpperBoundMillis,Long p95UpperBoundMillis,long maximumMillis) {}
    private final AtomicLongArray histogram=new AtomicLongArray(BOUNDS.length);
    private final AtomicLong completed=new AtomicLong(),inFlight=new AtomicLong(),errors=new AtomicLong(),limited=new AtomicLong(),max=new AtomicLong();
    private final Instant since;
    public RequestMetrics(Clock clock) {since=clock.instant();}
    public void begin() {inFlight.incrementAndGet();}
    public void finish(long nanos,int status) {
        long millis=Math.max(0,nanos/1_000_000L);int bucket=0;while(millis>BOUNDS[bucket]) bucket++;
        histogram.incrementAndGet(bucket);max.accumulateAndGet(millis,Math::max);
        if(status>=500) errors.incrementAndGet();if(status==429) limited.incrementAndGet();completed.incrementAndGet();inFlight.decrementAndGet();
    }
    public Snapshot snapshot() {long count=completed.get();return new Snapshot(since,count,inFlight.get(),errors.get(),limited.get(),percentile(count,.5),percentile(count,.95),max.get());}
    private Long percentile(long count,double fraction) {
        if(count==0) return null;long rank=(long)Math.ceil(count*fraction),seen=0;
        for(int i=0;i<BOUNDS.length;i++) {seen+=histogram.get(i);if(seen>=rank) return BOUNDS[i]==Long.MAX_VALUE?max.get():BOUNDS[i];}return max.get();
    }
}
