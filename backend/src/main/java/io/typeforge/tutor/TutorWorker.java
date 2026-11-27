package io.typeforge.tutor;

import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.tutor.TutorAnswerProvider.Answer;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fixed bounded tutoring workflow. External calls never occupy the shared scheduler or a DB lock. */
@Component
public final class TutorWorker {
    private static final Logger LOG=LoggerFactory.getLogger(TutorWorker.class);
    private final TutorService service;
    private final LocalKnowledgeSearch search;
    private final HttpTutorModelProvider provider;
    private final TutorSettings settings;
    private final Semaphore slots=new Semaphore(2);
    private final ExecutorService executor=Executors.newFixedThreadPool(2,task->{
        Thread thread=new Thread(task,"typeforge-tutor-worker");thread.setDaemon(true);return thread;
    });
    public TutorWorker(TutorService service,LocalKnowledgeSearch search,HttpTutorModelProvider provider,TutorSettings settings) {
        this.service=service;this.search=search;this.provider=provider;this.settings=settings;
    }
    @Scheduled(fixedDelayString="${typeforge.tutor.poll-millis:1000}")
    public void dispatch() {
        if(!settings.workerEnabled()) return;
        for(int i=0;i<2 && slots.tryAcquire();i++) {
            try { executor.execute(()->{
                try { runOne(); }
                catch(RuntimeException unavailable) { LOG.warn("Tutor queue is temporarily unavailable; requests retain their persisted recovery state"); }
                finally {slots.release();}
            }); }
            catch(java.util.concurrent.RejectedExecutionException stopped) { slots.release();return; }
        }
    }
    /** In-process worker/testing entry point; there is no learner HTTP dispatch/complete endpoint. */
    public boolean runOne() {
        TutorService.Ticket ticket=service.claimNext();
        if(ticket==null) return false;
        try {
            TeachingTurn turn=service.teachingTurn(ticket);
            var saved=turn.runId()==null?null:service.recovered(ticket);
            TutorAnswerProvider.Prompt prompt;
            if(saved!=null) prompt=saved.prompt();
            else {
                var evidence=search.search(ticket.releaseId(),turn.searchText(),service.teachingConcept(turn),4).items();
                prompt=service.prepare(ticket,evidence,turn);
                if(turn.runId()!=null) saved=service.recovered(ticket);
            }
            if(prompt.evidence().isEmpty()) { service.fail(ticket,"INSUFFICIENT_EVIDENCE",null);return true; }
            Answer answer;
            if(ticket.mode()==TutorService.Mode.EVIDENCE_ONLY) {
                answer=service.localAnswer(turn,prompt);
            } else {
                byte[] body=saved!=null && saved.body()!=null?saved.body().getBytes(java.nio.charset.StandardCharsets.UTF_8):provider.body(prompt);
                service.markDispatched(ticket);
                answer=provider.send(prompt,body);
            }
            service.accept(ticket,answer);
        } catch(TutorAnswerProvider.Failure failure) { service.fail(ticket,failure.code(),failure.usage()); }
        catch(DomainException failure) {
            service.fail(ticket,failure.code().startsWith("TUTOR_") || failure.status()==503?failure.code():"TUTOR_CONTEXT_CHANGED",null);
        } catch(RuntimeException failure) {
            // Do not persist or log provider bodies, questions, SQL parameters or credentials.
            LOG.warn("Tutor processing could not finish request {}; its persisted lease allows recovery",ticket.id());
            try { service.fail(ticket,"TUTOR_INTERNAL_ERROR",null); }
            catch(RuntimeException unavailable) { LOG.warn("Tutor failure state could not be stored for request {}",ticket.id()); }
        }
        return true;
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
