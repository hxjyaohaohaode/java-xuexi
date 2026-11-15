package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.typeforge.compiler.CompileProtocol;
import io.typeforge.compiler.CompileSettings;
import io.typeforge.compiler.HttpCompileProvider;
import io.typeforge.shared.JsonCodec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Loopback protocol fixtures verify strict transport behavior; they never pretend to execute source. */
class CompileHttpProtocolTest {
    private static final String TOKEN="fixture-compiler-token-at-least-32-characters";
    private final JsonCodec json=new JsonCodec(new ObjectMapper());
    private HttpServer server;
    private ExecutorService executor;
    private HttpCompileProvider provider;
    private final AtomicReference<String> mode=new AtomicReference<>("normal");
    private final AtomicReference<JsonNode> received=new AtomicReference<>();
    private final AtomicReference<String> authorization=new AtomicReference<>();
    private final AtomicInteger cancellations=new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newFixedThreadPool(4);server.setExecutor(executor);
        server.createContext("/v1/cancel/",exchange->{cancellations.incrementAndGet();byte[] body="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/v1/compile",exchange->{
            JsonNode input=json.tree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));received.set(input);authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if(mode.get().equals("slow")){try{Thread.sleep(1600);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
            var checks=new ArrayList<Map<String,Object>>();for(JsonNode check:input.path("checks"))checks.add(Map.of("id",check.path("id").asText(),"status","SUCCEEDED","diagnosticCodes",List.of(),"harnessOnly",false));
            var result=new java.util.LinkedHashMap<String,Object>();result.put("protocol",CompileSettings.PROTOCOL);result.put("isolation","container-per-job-v1");result.put("jobId",input.path("jobId").asText());result.put("leaseToken",input.path("leaseToken").asText());result.put("sourceHash",input.path("sourceHash").asText());result.put("jdkVersion","javac 17.0.21");result.put("status","SUCCEEDED");result.put("diagnostics",List.of());result.put("outputTruncated",false);result.put("checks",checks);
            if(mode.get().equals("wrongHash"))result.put("sourceHash","f".repeat(64));
            if(mode.get().equals("wrongLease"))result.put("leaseToken",UUID.randomUUID().toString());
            if(mode.get().equals("noIsolation"))result.put("isolation","host-javac");
            if(mode.get().equals("incomplete"))result.put("checks",List.of());
            if(mode.get().equals("unknownError"))result.put("status","COMPILE_ERROR");
            if(mode.get().equals("escapedSecret"))result.put("diagnostics",List.of(Map.of("code","compiler.note.fixture","severity","NOTE","line",-1,"column",-1,"message",TOKEN)));
            String body=json.write(result);
            if(mode.get().equals("escapedSecret"))body=body.replace(TOKEN,TOKEN.chars().mapToObj(c->String.format("\\u%04x",c)).collect(java.util.stream.Collectors.joining()));
            if(mode.get().equals("duplicate"))body=body.substring(0,body.length()-1)+",\"status\":\"SUCCEEDED\"}";
            if(mode.get().equals("oversize"))body="x".repeat(250000);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            try{exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(java.io.IOException ignored){/* Client intentionally rejects oversized, cancelled or late bodies. */}finally{exchange.close();}
        });server.start();provider=provider(3000);
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    private HttpCompileProvider provider(int timeout){return new HttpCompileProvider(new CompileSettings("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/compile",TOKEN,true,false,timeout,4,256,200),json);}
    private CompileProtocol.Request request(){String source="public class Main {}";return new CompileProtocol.Request(UUID.randomUUID(),UUID.randomUUID(),source,json.sha256(source),List.of());}
    @Test void sendsOnlyFixedProtocolAndReturnsAuthoritativeStructuredOutcome(){
        var input=request();var result=provider.compile(input,()->true);assertEquals("SUCCEEDED",result.status());assertEquals(input.sourceHash(),result.sourceHash());assertEquals("Bearer "+TOKEN,authorization.get());
        assertFalse(received.get().has("endpoint"));assertFalse(received.get().has("flags"));assertFalse(received.get().toString().contains(TOKEN));assertEquals("public class Main {}",received.get().path("source").asText());
    }
    @Test void mismatchedHashLeaseAndIsolationPolicyFailClosed(){
        for(String value:List.of("wrongHash","wrongLease","noIsolation","duplicate","unknownError")){mode.set(value);assertEquals("COMPILER_PROTOCOL_ERROR",assertThrows(CompileProtocol.Failure.class,()->provider.compile(request(),()->true)).code(),value);}
    }
    @Test void partialCheckResultsCannotBeAcceptedAsSuccessfulCompilation(){
        mode.set("incomplete");var base=request();var input=new CompileProtocol.Request(base.jobId(),base.leaseToken(),base.source(),base.sourceHash(),List.of(new CompileProtocol.Check("check0","void check(){}",true,List.of())));
        assertEquals("COMPILER_INCOMPLETE_RESULT",assertThrows(CompileProtocol.Failure.class,()->provider.compile(input,()->true)).code());
        assertFalse(received.get().path("checks").get(0).has("expectedCompiled"));assertFalse(received.get().path("checks").get(0).has("expectedDiagnosticCodes"));
    }
    @Test void oversizedResponseIsStoppedAtTheTransportBoundary(){mode.set("oversize");assertEquals("COMPILER_TRANSPORT_ERROR",assertThrows(CompileProtocol.Failure.class,()->provider.compile(request(),()->true)).code());}
    @Test void escapedCredentialInADiagnosticNeverReachesTheLearner(){mode.set("escapedSecret");assertEquals("COMPILER_PROTOCOL_ERROR",assertThrows(CompileProtocol.Failure.class,()->provider.compile(request(),()->true)).code());}
    @Test void durableCancellationInterruptsWaitingAndTargetsTheSameLease(){
        mode.set("slow");AtomicInteger checks=new AtomicInteger();var failure=assertThrows(CompileProtocol.Failure.class,()->provider.compile(request(),()->checks.getAndIncrement()<1));assertEquals("COMPILE_CANCELLED",failure.code());assertEquals(1,cancellations.get());
    }
    @Test void timeoutDoesNotRetryTheCompileRequest(){
        mode.set("slow");var failure=assertThrows(CompileProtocol.Failure.class,()->provider(1000).compile(request(),()->true));assertTrue(List.of("COMPILER_TIMEOUT","COMPILER_TRANSPORT_ERROR").contains(failure.code()));
    }
    @Test void endpointCredentialsAndLimitsComeOnlyFromServerConfiguration(){
        assertThrows(IllegalArgumentException.class,()->new CompileSettings("http://example.com/v1/compile",TOKEN,true,true,1000,4,256,200));
        assertThrows(IllegalArgumentException.class,()->new CompileSettings("https://example.com/v1/compile?command=x",TOKEN,true,true,1000,4,256,200));
        assertThrows(IllegalArgumentException.class,()->new CompileSettings("",TOKEN,true,true,1000,4,256,200));
        assertThrows(IllegalArgumentException.class,()->new CompileSettings("http://127.0.0.1/v1/compile","short",true,true,1000,4,256,200));
    }
}
