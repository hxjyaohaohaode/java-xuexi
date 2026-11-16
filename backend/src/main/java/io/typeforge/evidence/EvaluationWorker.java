package io.typeforge.evidence;

import io.typeforge.shared.DomainException;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded CPU worker, separate from the shared scheduler; durable expired work is explicit failure. */
@Component
public final class EvaluationWorker {
    private static final Logger LOG=LoggerFactory.getLogger(EvaluationWorker.class);
    private final EvaluationService service;
    private final boolean enabled;
    private final Semaphore slot=new Semaphore(1);
    private final ExecutorService executor=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"typeforge-evaluation");thread.setDaemon(true);return thread;});
    public EvaluationWorker(EvaluationService service,@Value("${typeforge.evaluation.worker-enabled:true}") boolean enabled){this.service=service;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${typeforge.evaluation.poll-millis:1000}")
    public void dispatch(){if(!enabled || !slot.tryAcquire())return;try{executor.execute(()->{try{runOne();}catch(RuntimeException unavailable){LOG.warn("Evaluation queue temporarily unavailable; durable state retained");}finally{slot.release();}});}catch(java.util.concurrent.RejectedExecutionException stopped){slot.release();}}
    public boolean runOne(){
        var ticket=service.claimNext();if(ticket==null)return false;long start=System.nanoTime();
        try{
            service.validateCorpus(ticket);
            for(int i=0;i<ticket.snapshot().cases().size();i++){
                if(Thread.currentThread().isInterrupted() || elapsed(start)>EvaluationService.MAX_RUNTIME_MILLIS){service.fail(ticket,"EVALUATION_TIME_LIMIT");return true;}
                if(!service.active(ticket)){service.fail(ticket,"EVALUATION_SCOPE_INACTIVE");return true;}
                var result=service.evaluate(ticket,ticket.snapshot().cases().get(i));
                if(elapsed(start)>EvaluationService.MAX_RUNTIME_MILLIS){service.fail(ticket,"EVALUATION_TIME_LIMIT");return true;}
                if(!service.acceptCase(ticket,i,result))return true;
            }
            service.complete(ticket,elapsed(start));
        }catch(DomainException failure){service.fail(ticket,failure.code());}
        catch(RuntimeException failure){LOG.warn("Evaluation execution could not finish; no complete metrics were produced");service.fail(ticket,"EVALUATION_INTERNAL_ERROR");}
        return true;
    }
    private static long elapsed(long start){return Math.max(0,(System.nanoTime()-start)/1_000_000);}
    @PreDestroy public void close(){executor.shutdownNow();}
}
