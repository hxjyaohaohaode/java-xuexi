package io.typeforge.tutor;

import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.tutor.TutorAnswerProvider.Answer;
import io.typeforge.tutor.TutorAnswerProvider.Usage;
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
            String query=ticket.question().length()>500?ticket.question().substring(0,500):ticket.question();
            var evidence=search.search(ticket.releaseId(),query,null,4).items();
            var prompt=service.prepare(ticket,evidence);
            if(evidence.isEmpty()) { service.fail(ticket,"INSUFFICIENT_EVIDENCE",null);return true; }
            Answer answer;
            if(ticket.mode()==TutorService.Mode.EVIDENCE_ONLY) {
                var text=new StringBuilder("本地证据整理（未调用语言模型）\n\n");
                int index=0;
                for(var source:evidence) {
                    String excerpt=source.excerpt().length()>1500?source.excerpt().substring(0,1500):source.excerpt();
                    text.append(++index).append(". ").append(source.title()).append("\n").append(excerpt).append("\n\n");
                }
                answer=new Answer(text.toString(),evidence.stream().map(source->source.chunkId()).toList(),"可打开下方版本化来源核对原文；需要连贯的个性化模型讲解时，请显式选择已配置的模型模式。",new Usage(0,0,0));
            } else {
                byte[] body=provider.body(prompt);
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
