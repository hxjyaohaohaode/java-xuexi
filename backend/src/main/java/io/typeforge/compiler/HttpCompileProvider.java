package io.typeforge.compiler;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.typeforge.operations.RuntimeCapabilityProvider;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Compiler transport only: no ProcessBuilder, JavaCompiler or user bytecode exists in the API JVM. */
@Component
public final class HttpCompileProvider implements RuntimeCapabilityProvider {
    private static final int MAX_RESPONSE=196608;
    private static final Set<String> STATUSES=Set.of("SUCCEEDED","COMPILE_ERROR","TIMED_OUT","RESOURCE_LIMIT","INFRA_ERROR","CANCELLED");
    private final CompileSettings settings;
    private final JsonCodec json;
    private final HttpClient http;
    private final ObjectMapper parser;
    public HttpCompileProvider(CompileSettings settings,JsonCodec json) {
        this.settings=settings;this.json=json;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        parser=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        parser.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_RESPONSE).maxNumberLength(32).build());
    }
    @Override public Set<String> enabledCapabilities() {return settings.configured()?Set.of("COMPILE_ONLY","CODE_GRADING"):Set.of();}
    public String fingerprint() {return settings.configured()?json.hash(Map.of("endpoint",settings.endpoint().toString(),"protocol",CompileSettings.PROTOCOL,"isolation","container-per-job-v1")):null;}
    public void requireConfigured() {if(!settings.configured()) throw new DomainException("COMPILER_UNAVAILABLE",503,"An explicitly enabled isolated compiler worker is required");}
    public CompileProtocol.Result compile(CompileProtocol.Request input,BooleanSupplier active) {
        requireConfigured();
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Compiler HTTP must not run inside a database transaction");
        var checks=input.checks().stream().map(check->Map.of("id",check.id(),"source",check.source())).toList();
        byte[] body=json.write(Map.of("protocol",CompileSettings.PROTOCOL,"jobId",input.jobId(),"leaseToken",input.leaseToken(),"source",input.source(),"sourceHash",input.sourceHash(),"checks",checks)).getBytes(StandardCharsets.UTF_8);
        if(body.length>131072) throw new CompileProtocol.Failure("COMPILER_REQUEST_TOO_LARGE");
        HttpRequest request=HttpRequest.newBuilder(settings.endpoint()).timeout(Duration.ofMillis(settings.timeoutMillis()))
            .header("Content-Type","application/json").header("Accept","application/json").header("Authorization","Bearer "+settings.token())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var pending=http.sendAsync(request,ignored->new LimitedBody(MAX_RESPONSE));
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(settings.timeoutMillis());
        try {
            while(true) {
                if(!active.getAsBoolean()) {pending.cancel(true);cancel(input.jobId(),input.leaseToken());throw new CompileProtocol.Failure("COMPILE_CANCELLED");}
                long remaining=deadline-System.nanoTime();
                if(remaining<=0) {pending.cancel(true);cancel(input.jobId(),input.leaseToken());throw new CompileProtocol.Failure("COMPILER_TIMEOUT");}
                try {
                    var response=pending.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(300)),TimeUnit.NANOSECONDS);
                    if(response.statusCode()!=200) throw new CompileProtocol.Failure("COMPILER_HTTP_ERROR");
                    if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");
                    return parse(response.body(),input);
                } catch(TimeoutException waiting) { /* Check durable cancellation while the independent worker is busy. */ }
            }
        } catch(InterruptedException error) {pending.cancel(true);cancel(input.jobId(),input.leaseToken());Thread.currentThread().interrupt();throw new CompileProtocol.Failure("COMPILER_INTERRUPTED");}
        catch(ExecutionException error) {
            if(error.getCause() instanceof java.net.http.HttpTimeoutException) {cancel(input.jobId(),input.leaseToken());throw new CompileProtocol.Failure("COMPILER_TIMEOUT");}
            throw new CompileProtocol.Failure("COMPILER_TRANSPORT_ERROR");
        }
        finally {if(!pending.isDone()) pending.cancel(true);}
    }
    private CompileProtocol.Result parse(byte[] bytes,CompileProtocol.Request request) {
        try {
            JsonNode root=parser.readTree(bytes);
            if(root==null || !root.isObject() || !CompileSettings.PROTOCOL.equals(root.path("protocol").asText())
                || !"container-per-job-v1".equals(root.path("isolation").asText())
                || !request.jobId().toString().equals(root.path("jobId").asText()) || !request.leaseToken().toString().equals(root.path("leaseToken").asText())
                || !request.sourceHash().equals(root.path("sourceHash").asText()) || !STATUSES.contains(root.path("status").asText())
                || !root.path("jdkVersion").isTextual() || !root.path("jdkVersion").asText().matches("[A-Za-z0-9 ._+()\\-]{1,64}")
                || !root.path("outputTruncated").isBoolean() || !root.path("diagnostics").isArray() || root.path("diagnostics").size()>100
                || !root.path("checks").isArray() || root.path("checks").size()>8) throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");
            var diagnostics=new ArrayList<CompilationGateway.Diagnostic>();
            for(JsonNode row:root.path("diagnostics")) {
                if(!row.path("code").isTextual() || !row.path("code").asText().matches("[A-Za-z0-9_.-]{1,128}")
                    || !Set.of("ERROR","WARNING","NOTE").contains(row.path("severity").asText())
                    || !row.path("line").isIntegralNumber() || row.path("line").asLong() < -1 || row.path("line").asLong()>1000000
                    || !row.path("column").isIntegralNumber() || row.path("column").asLong() < -1 || row.path("column").asLong()>1000000
                    || !row.path("message").isTextual() || row.path("message").asText().length()>1000) throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");
                diagnostics.add(new CompilationGateway.Diagnostic(row.path("code").asText(),row.path("severity").asText(),row.path("line").asLong(),row.path("column").asLong(),row.path("message").asText()));
            }
            var checkResults=new ArrayList<CompileProtocol.CheckResult>();var seen=new HashSet<String>();
            Set<String> allowed=request.checks().stream().map(CompileProtocol.Check::id).collect(java.util.stream.Collectors.toSet());
            for(JsonNode row:root.path("checks")) {
                if(!allowed.contains(row.path("id").asText()) || !seen.add(row.path("id").asText()) || !STATUSES.contains(row.path("status").asText())
                    || !row.path("harnessOnly").isBoolean() || !row.path("diagnosticCodes").isArray() || row.path("diagnosticCodes").size()>100) throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");
                var codes=new ArrayList<String>();
                for(JsonNode code:row.path("diagnosticCodes")) {if(!code.isTextual() || !code.asText().matches("[A-Za-z0-9_.-]{1,128}")) throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");codes.add(code.asText());}
                if(row.path("status").asText().equals("SUCCEEDED") && !codes.isEmpty() || row.path("status").asText().equals("COMPILE_ERROR") && codes.isEmpty()) throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");
                checkResults.add(new CompileProtocol.CheckResult(row.path("id").asText(),row.path("status").asText(),codes,row.path("harnessOnly").asBoolean()));
            }
            if(root.path("status").asText().equals("SUCCEEDED") && (!seen.equals(allowed) || diagnostics.stream().anyMatch(d->!d.severity().equals("NOTE")) || root.path("outputTruncated").asBoolean())) throw new CompileProtocol.Failure("COMPILER_INCOMPLETE_RESULT");
            if(root.path("status").asText().equals("COMPILE_ERROR") && diagnostics.stream().noneMatch(d->d.severity().equals("ERROR"))) throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");
            String text=new String(bytes,StandardCharsets.UTF_8);
            // Check decoded public fields too: escaped JSON must not bypass credential-echo rejection.
            if(text.contains(settings.token()) || root.path("jdkVersion").asText().contains(settings.token())
                || diagnostics.stream().anyMatch(d->d.message().contains(settings.token()) || d.code().contains(settings.token()))) throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");
            return new CompileProtocol.Result(root.path("status").asText(),request.sourceHash(),root.path("jdkVersion").asText(),diagnostics,root.path("outputTruncated").asBoolean(),checkResults);
        } catch(CompileProtocol.Failure failure) {throw failure;}
        catch(java.io.IOException | IllegalArgumentException failure) {throw new CompileProtocol.Failure("COMPILER_PROTOCOL_ERROR");}
    }
    private void cancel(UUID jobId,UUID lease) {
        if(Thread.currentThread().isInterrupted()) return;
        try {
            var request=HttpRequest.newBuilder(settings.endpoint().resolve("/v1/cancel/"+jobId)).timeout(Duration.ofSeconds(2))
                .header("Authorization","Bearer "+settings.token()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(Map.of("leaseToken",lease)))).build();
            http.send(request,HttpResponse.BodyHandlers.discarding());
        } catch(InterruptedException error) {Thread.currentThread().interrupt();}
        catch(java.io.IOException error) { /* Durable cancellation remains authoritative; worker wall limit still applies. */ }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription next){subscription=next;next.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){if(buffer.remaining()>limit-bytes.size()){subscription.cancel();result.completeExceptionally(new CompileProtocol.Failure("COMPILER_RESPONSE_TOO_LARGE"));return;}byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);}
            subscription.request(1);
        }
        @Override public void onError(Throwable failure){result.completeExceptionally(failure);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
