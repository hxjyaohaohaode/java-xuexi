package io.typeforge.compiler;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class CompileWorker {
    private static final Logger LOG=LoggerFactory.getLogger(CompileWorker.class);
    private final CompileService service;
    private final HttpCompileProvider provider;
    private final CompileSettings settings;
    private final Semaphore slots=new Semaphore(2);
    private final ExecutorService executor=Executors.newFixedThreadPool(2,task->{Thread thread=new Thread(task,"typeforge-compile-transport");thread.setDaemon(true);return thread;});
    public CompileWorker(CompileService service,HttpCompileProvider provider,CompileSettings settings){this.service=service;this.provider=provider;this.settings=settings;}
    @Scheduled(fixedDelayString="${typeforge.compiler.poll-millis:1000}")
    public void dispatch(){
        if(!settings.dispatchEnabled()) return;
        for(int i=0;i<2 && slots.tryAcquire();i++) {
            try{executor.execute(()->{try{runOne();}catch(RuntimeException failure){LOG.warn("Compiler queue temporarily unavailable; durable state retained");}finally{slots.release();}});}
            catch(java.util.concurrent.RejectedExecutionException stopped){slots.release();return;}
        }
    }
    public boolean runOne(){
        CompileService.Ticket ticket=service.claimNext();if(ticket==null)return false;
        try{service.accept(ticket,provider.compile(ticket.request(),()->service.active(ticket)));}
        catch(CompileProtocol.Failure failure){service.fail(ticket,failure.code());}
        catch(RuntimeException failure){service.fail(ticket,"COMPILER_INTERNAL_ERROR");}
        return true;
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
