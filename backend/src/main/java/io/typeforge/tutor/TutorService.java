package io.typeforge.tutor;

import io.typeforge.curriculum.CurriculumService.EvidenceView;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.tutor.TutorAnswerProvider.Answer;
import io.typeforge.tutor.TutorAnswerProvider.Prompt;
import io.typeforge.tutor.TutorAnswerProvider.Usage;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable tutoring with owner-first admission, bounded quotas and fenced external results. */
@Service
public final class TutorService {
    public enum Mode { EVIDENCE_ONLY, MODEL }
    public record ConversationInput(UUID workspaceId,UUID releaseId,String title) {}
    public record TutorInput(UUID conversationId,UUID snapshotId,Mode mode,String question,int hintLevel,UUID attemptId) {}
    public record ConversationSummary(UUID id,UUID workspaceId,UUID releaseId,String title,Instant createdAt) {}
    public record ConversationList(List<ConversationSummary> items,UUID nextCursor) {}
    public record ConversationView(UUID id,UUID workspaceId,UUID releaseId,String title,Instant createdAt,List<TutorResult> messages,UUID nextCursor) {}
    public record UsageView(String billingState,long reservedTokens,Long usedTokens) {}
    public record TutorResult(UUID id,UUID conversationId,UUID workspaceId,UUID snapshotId,Mode mode,String status,long epoch,
                              String question,int hintLevel,String answer,List<EvidenceView> evidenceRefs,List<UUID> diagnosticRefs,
                              List<UUID> suggestedExerciseIds,String limitations,String errorCode,String sourceState,
                              UsageView usage,Instant createdAt,Instant completedAt) {}
    public record Ticket(UUID id,UUID ownerId,UUID workspaceId,UUID releaseId,UUID conversationId,UUID snapshotId,
                         Mode mode,String question,int hintLevel,UUID attemptId,long epoch,UUID token,Instant leaseUntil) {}
    private record Stored(UUID id,UUID owner,UUID workspace,UUID release,UUID conversation,UUID snapshot,Mode mode,String question,int hintLevel,
                          UUID attempt,String status,long epoch,UUID token,Instant leaseUntil,Instant deadline,int attempts,String contextHash,
                          List<EvidenceView> evidence,String fingerprint,long reserved,Long used,String billing,LocalDate budgetDate,
                          Instant dispatched,String resultHash,String error,String answer,String limitations,Instant createdAt,Instant completedAt) {}
    private static final Set<String> ACTIVE=Set.of("QUEUED","RETRIEVING","GENERATING","VALIDATING");
    private static final String SELECT="SELECT r.id,c.user_id,x.workspace_id,c.release_id,r.conversation_id,x.snapshot_id,x.mode,r.question,r.hint_level,r.attempt_id,r.status,x.request_epoch,x.lease_token,x.lease_until,x.deadline_at,x.attempt_count,x.context_hash,x.evidence_refs,x.provider_fingerprint,x.reserved_tokens,x.used_tokens,x.billing_state,x.budget_date,x.dispatched_at,x.result_hash,r.error_code,m.answer,m.limitations,r.created_at,x.completed_at FROM tutor_request r JOIN tutor_request_runtime x ON x.request_id=r.id JOIN conversation c ON c.id=r.conversation_id JOIN workspace_conversation wc ON wc.conversation_id=c.id AND wc.workspace_id=x.workspace_id LEFT JOIN tutor_message m ON m.request_id=r.id ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final EventJournal events;
    private final JsonCodec json;
    private final Clock clock;
    private final TutorSettings settings;
    private final HttpTutorModelProvider provider;
    public TutorService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,EventJournal events,JsonCodec json,Clock clock,TutorSettings settings,HttpTutorModelProvider provider) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.events=events;this.json=json;this.clock=clock;this.settings=settings;this.provider=provider;
    }

    TeachingTurn teachingTurn(Ticket ticket) { return TeachingTurn.resolve(jdbc,json,ticket); }
    TutorAnswerProvider.Answer localAnswer(TeachingTurn turn,Prompt prompt) { return turn.localAnswer(jdbc,json,prompt); }

    public ConversationSummary createConversation(UUID owner,ConversationInput input,String key) {
        WorkspaceService.requireKey(key);
        if(input==null || input.workspaceId()==null || input.releaseId()==null) throw DomainException.invalid("A workspace and its fixed release are required");
        WorkspaceService.requireText(input.title(),128);
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,input.workspaceId());
            String hash=json.hash(input);UUID replay=workspaces.replay(owner,"tutor.conversation",key,hash);
            if(replay!=null) return summary(owner,replay);
            if(!scope.releaseId().equals(input.releaseId())) throw DomainException.invalid("Conversation release must match the workspace");
            requirePublished(input.releaseId());
            UUID id=UUID.randomUUID();Instant now=clock.instant();
            jdbc.update("INSERT INTO conversation(id,user_id,release_id,title,created_at) VALUES(?,?,?,?,?)",id.toString(),owner.toString(),input.releaseId().toString(),input.title(),Timestamp.from(now));
            jdbc.update("INSERT INTO workspace_conversation(conversation_id,workspace_id) VALUES(?,?)",id.toString(),input.workspaceId().toString());
            workspaces.remember(owner,"tutor.conversation",key,hash,id);
            events.append(input.workspaceId(),"TUTOR_CONVERSATION_CREATED","CLIENT",Map.of("conversationId",id));
            return summary(owner,id);
        });
    }
    public ConversationList conversations(UUID owner,UUID workspace,UUID cursor,int limit) {
        page(limit);
        if(workspace!=null) workspaces.requireOwned(owner,workspace);
        var parameters=new ArrayList<Object>();parameters.add(owner.toString());
        String condition=" WHERE c.user_id=?";
        if(workspace!=null) { condition+=" AND wc.workspace_id=?";parameters.add(workspace.toString()); }
        if(cursor!=null) {
            var after=summary(owner,cursor);
            if(workspace!=null && !workspace.equals(after.workspaceId())) throw DomainException.invalid("Cursor belongs to another workspace");
            condition+=" AND (c.created_at<? OR (c.created_at=? AND c.id<?))";
            parameters.add(Timestamp.from(after.createdAt()));parameters.add(Timestamp.from(after.createdAt()));parameters.add(after.id().toString());
        }
        parameters.add(limit+1);
        var rows=jdbc.query("SELECT c.id,wc.workspace_id,c.release_id,c.title,c.created_at FROM conversation c JOIN workspace_conversation wc ON wc.conversation_id=c.id JOIN learning_workspace w ON w.id=wc.workspace_id AND w.owner_id=c.user_id"+condition+" ORDER BY c.created_at DESC,c.id DESC LIMIT ?",this::conversationRow,parameters.toArray());
        var items=rows.stream().limit(limit).toList();
        return new ConversationList(items,rows.size()>limit?items.get(items.size()-1).id():null);
    }
    public ConversationView conversation(UUID owner,UUID id,UUID cursor,int limit) {
        page(limit);var conversation=summary(owner,id);var parameters=new ArrayList<Object>();parameters.add(owner.toString());parameters.add(id.toString());
        String condition=" WHERE c.user_id=? AND r.conversation_id=?";
        if(cursor!=null) {
            Stored after=owned(owner,cursor);
            if(!after.conversation().equals(id)) throw DomainException.invalid("Cursor belongs to another conversation");
            condition+=" AND (r.created_at<? OR (r.created_at=? AND r.id<?))";
            parameters.add(Timestamp.from(after.createdAt()));parameters.add(Timestamp.from(after.createdAt()));parameters.add(after.id().toString());
        }
        parameters.add(limit+1);
        var rows=jdbc.query(SELECT+condition+" ORDER BY r.created_at DESC,r.id DESC LIMIT ?",this::requestRow,parameters.toArray());
        var messages=rows.stream().limit(limit).map(this::view).toList();
        return new ConversationView(conversation.id(),conversation.workspaceId(),conversation.releaseId(),conversation.title(),conversation.createdAt(),messages,rows.size()>limit?messages.get(messages.size()-1).id():null);
    }
    public TutorResult submit(UUID owner,TutorInput input,String key) {
        WorkspaceService.requireKey(key);
        if(input==null || input.conversationId()==null || input.snapshotId()==null || input.mode()==null || input.hintLevel()<0 || input.hintLevel()>4) throw DomainException.invalid("A conversation, snapshot, mode and bounded hint level are required");
        WorkspaceService.requireText(input.question(),4000);
        return tx.execute(status->{
            var conversation=summary(owner,input.conversationId());
            workspaces.lockOwned(owner,conversation.workspaceId());
            String hash=json.hash(input);UUID replay=workspaces.replay(owner,"tutor.request",key,hash);
            if(replay!=null) return view(owned(owner,replay));
            requirePublished(conversation.releaseId());
            var context=workspaces.readContext(owner,conversation.workspaceId(),input.snapshotId());
            if(input.attemptId()!=null && context.references().stream().noneMatch(ref->ref.kind().equals("ATTEMPT") && ref.id().equals(input.attemptId()))) throw DomainException.invalid("Attempt must be part of the selected owned snapshot");
            if(input.mode()==Mode.MODEL) provider.requireConfigured();
            Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM tutor_request r JOIN conversation c ON c.id=r.conversation_id WHERE c.user_id=? AND r.status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING')",Integer.class,owner.toString());
            if(pending!=null && pending>=settings.maxPending()) throw new DomainException("TUTOR_PENDING_LIMIT",429,"Finish or cancel an existing tutor request before starting another");
            Instant now=clock.instant();LocalDate day=now.atZone(ZoneOffset.UTC).toLocalDate();long reservation=input.mode()==Mode.MODEL?settings.reservationTokens():0;
            reserve(owner,day,reservation);
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO tutor_request(id,conversation_id,attempt_id,question,hint_level,status,prompt_version,model_id,created_at) VALUES(?,?,?,?,?,'QUEUED','evidence-tutor-v1',?,?)",
                id.toString(),input.conversationId().toString(),string(input.attemptId()),input.question(),input.hintLevel(),input.mode()==Mode.MODEL?settings.model():null,Timestamp.from(now));
            jdbc.update("INSERT INTO tutor_request_runtime(request_id,workspace_id,snapshot_id,mode,deadline_at,input_hash,context_hash,evidence_refs,provider_fingerprint,reserved_tokens,billing_state,budget_date) VALUES(?,?,?,?,?,?,?,'[]',?,?,?,?)",
                id.toString(),conversation.workspaceId().toString(),input.snapshotId().toString(),input.mode().name(),Timestamp.from(now.plusSeconds(120)),hash,context.contentHash(),input.mode()==Mode.MODEL?provider.fingerprint():null,reservation,reservation>0?"RESERVED":"NONE",day);
            workspaces.remember(owner,"tutor.request",key,hash,id);
            events.append(conversation.workspaceId(),"TUTOR_REQUEST_QUEUED","CLIENT",Map.of("requestId",id,"conversationId",conversation.id(),"mode",input.mode().name(),"epoch",1));
            return view(owned(owner,id));
        });
    }
    public TutorResult get(UUID owner,UUID id) { return view(owned(owner,id)); }
    public TutorResult cancel(UUID owner,UUID id,long expectedEpoch) {
        if(expectedEpoch<1 || expectedEpoch>9_007_199_254_740_991L) throw DomainException.invalid("A bounded expected epoch is required");
        return tx.execute(status->{
            Stored row=lock(owner,id);
            if(row.status().equals("CANCELLED") && (expectedEpoch==row.epoch() || expectedEpoch==row.epoch()-1)) return view(row);
            if(row.epoch()!=expectedEpoch) throw DomainException.conflict("Tutor request changed before cancellation");
            if(!ACTIVE.contains(row.status())) throw DomainException.conflict("Tutor request is already terminal");
            terminal(row,"CANCELLED","TUTOR_CANCELLED");
            return view(owned(owner,id));
        });
    }

    /** Internal worker admission. Old provider dispatches become unknown; they are never replayed. */
    public Ticket claimNext() {
        var ids=jdbc.queryForList("SELECT r.id FROM tutor_request r JOIN tutor_request_runtime x ON x.request_id=r.id JOIN conversation c ON c.id=r.conversation_id JOIN app_user u ON u.id=c.user_id JOIN learning_workspace w ON w.id=x.workspace_id AND w.owner_id=c.user_id WHERE r.status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING') AND (r.status='QUEUED' OR x.lease_until IS NULL OR x.lease_until<=? OR x.deadline_at<=? OR u.status<>'ACTIVE' OR w.status<>'ACTIVE') ORDER BY r.created_at,r.id LIMIT 32",String.class,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        for(String id:ids) {
            Ticket ticket=tx.execute(status->{
                var preview=jdbc.query(SELECT+" WHERE r.id=?",this::requestRow,id);
                if(preview.isEmpty()) return null;
                Stored row=lockWorker(preview.get(0).owner(),UUID.fromString(id));
                if(!ACTIVE.contains(row.status())) return null;
                if(!clock.instant().isBefore(row.deadline())) { terminal(row,"FAILED","TUTOR_DEADLINE_EXCEEDED");return null; }
                if(!scopeAvailable(row)) { terminal(row,"FAILED","TUTOR_SCOPE_UNAVAILABLE");return null; }
                if(!row.status().equals("QUEUED") && row.leaseUntil()!=null && clock.instant().isBefore(row.leaseUntil())) return null;
                if(row.dispatched()!=null) { terminal(row,"FAILED","TUTOR_PROVIDER_RESULT_UNKNOWN");return null; }
                if(row.attempts()>=3) { terminal(row,"FAILED","TUTOR_RETRY_EXHAUSTED");return null; }
                try {
                    checkSources(row);
                    if(row.mode()==Mode.MODEL && (!provider.readiness().configured() || !provider.fingerprint().equals(row.fingerprint()))) {
                        terminal(row,"FAILED","TUTOR_MODEL_UNAVAILABLE");return null;
                    }
                } catch(DomainException failure) {
                    if(!invalidSource(failure)) throw failure;
                    terminal(row,"FAILED","TUTOR_CONTEXT_CHANGED");return null;
                }
                long epoch=row.status().equals("QUEUED")?row.epoch():row.epoch()+1;
                UUID token=UUID.randomUUID();Instant expiry=clock.instant().plusMillis(row.mode()==Mode.MODEL?settings.timeoutMillis()+15000L:15000L);
                if(expiry.isAfter(row.deadline())) expiry=row.deadline();
                jdbc.update("UPDATE tutor_request_runtime SET request_epoch=?,lease_token=?,lease_until=?,attempt_count=attempt_count+1 WHERE request_id=?",epoch,token.toString(),Timestamp.from(expiry),id);
                jdbc.update("UPDATE tutor_request SET status='RETRIEVING',error_code=NULL WHERE id=?",id);
                events.append(row.workspace(),"TUTOR_REQUEST_STARTED","SERVER",Map.of("requestId",row.id(),"epoch",epoch));
                return new Ticket(row.id(),row.owner(),row.workspace(),row.release(),row.conversation(),row.snapshot(),row.mode(),row.question(),row.hintLevel(),row.attempt(),epoch,token,expiry);
            });
            if(ticket!=null) return ticket;
        }
        return null;
    }
    /** Reads authorized selected context; never reads exercise.answer_spec, tests, solutions or keys. */
    public Prompt prepare(Ticket ticket,List<EvidenceView> evidence) {
        return prepare(ticket,evidence,teachingTurn(ticket));
    }
    Prompt prepare(Ticket ticket,List<EvidenceView> evidence,TeachingTurn turn) {
        if(evidence==null || evidence.size()>4) throw DomainException.invalid("At most four tutor evidence cards are allowed");
        return tx.execute(status->{
            Stored row=lockWorker(ticket.ownerId(),ticket.id());requireLease(row,ticket);checkSources(row);
            validateEvidence(row,evidence);
            jdbc.update("UPDATE tutor_request_runtime SET evidence_refs=? WHERE request_id=?",json.write(evidence),row.id().toString());
            var context=workspaces.readContext(row.owner(),row.workspace(),row.snapshot());
            var learner=new ArrayList<Map<String,Object>>();int goals=0,memories=0;
            for(Reference ref:context.references()) {
                if(ref.kind().equals("GOAL") && goals++<2) learner.add(Map.of("kind","GOAL","id",ref.id(),"text",clip(workspaces.goal(row.owner(),row.workspace(),ref.id()).text(),500)));
                if(ref.kind().equals("MEMORY") && memories++<2) {
                    String memory=jdbc.queryForObject("SELECT content_json FROM learner_memory WHERE id=? AND workspace_id=? AND status='ACTIVE'",String.class,ref.id().toString(),row.workspace().toString());
                    learner.add(Map.of("kind","USER_STATED_MEMORY","id",ref.id(),"text",clip(json.tree(memory).path("summary").asText(),500)));
                }
            }
            if(row.attempt()!=null) {
                var attempts=jdbc.queryForList("SELECT a.answer,a.score FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id WHERE a.id=? AND a.user_id=? AND wa.workspace_id=? AND a.status='GRADED'",row.attempt().toString(),row.owner().toString(),row.workspace().toString());
                if(attempts.isEmpty()) throw DomainException.missing();
                learner.add(Map.of("kind","OWN_SUBMITTED_ATTEMPT","id",row.attempt(),"submittedAnswer",clip(json.tree(attempts.get(0).get("answer")).toString(),1000),"score",attempts.get(0).get("score")));
            }
            var recent=jdbc.queryForList("SELECT question FROM tutor_request WHERE conversation_id=? AND id<>? ORDER BY created_at DESC,id DESC LIMIT 4",String.class,row.conversation().toString(),row.id().toString()).stream().map(value->clip(value,500)).toList();
            return new Prompt(row.id(),row.question(),row.hintLevel(),recent,learner,evidence,TutorAnswerProvider.Purpose.valueOf(turn.role()));
        });
    }
    public void markDispatched(Ticket ticket) {
        tx.executeWithoutResult(status->{
            Stored row=lockWorker(ticket.ownerId(),ticket.id());requireLease(row,ticket);checkSources(row);
            if(row.mode()!=Mode.MODEL || row.dispatched()!=null || !provider.readiness().configured() || !provider.fingerprint().equals(row.fingerprint())) throw DomainException.conflict("Provider configuration or dispatch state changed");
            jdbc.update("UPDATE tutor_request_runtime SET dispatched_at=? WHERE request_id=?",Timestamp.from(clock.instant()),row.id().toString());
            jdbc.update("UPDATE tutor_request SET status='GENERATING' WHERE id=?",row.id().toString());
            events.append(row.workspace(),"TUTOR_MODEL_DISPATCHED","SERVER",Map.of("requestId",row.id(),"epoch",row.epoch()));
        });
    }
    public void accept(Ticket ticket,Answer answer) {
        tx.executeWithoutResult(status->{
            Stored row=lockWorker(ticket.ownerId(),ticket.id());
            if(row.dispatched()!=null && ticket.token().equals(row.token()) && answer.usage()!=null) {
                settle(row,answer.usage());
                if(answer.providerModelId()!=null && !answer.providerModelId().isBlank() && answer.providerModelId().length()<=128) jdbc.update("UPDATE tutor_request SET model_id=? WHERE id=?",answer.providerModelId(),row.id().toString());
            }
            String digest=json.hash(answer);
            if(row.status().equals("COMPLETED") && row.epoch()==ticket.epoch() && ticket.token().equals(row.token())) {
                if(!digest.equals(row.resultHash())) throw DomainException.conflict("A tutor result was already committed with different content");
                return;
            }
            if(!sameExecution(row,ticket) || !ACTIVE.contains(row.status())) return;
            if(!clock.instant().isBefore(row.deadline())) { terminal(raw(row.owner(),row.id()),"FAILED","TUTOR_DEADLINE_EXCEEDED");return; }
            if(row.leaseUntil()==null || !clock.instant().isBefore(row.leaseUntil())) { terminal(raw(row.owner(),row.id()),"FAILED","TUTOR_LEASE_EXPIRED");return; }
            if(!scopeAvailable(row)) { terminal(raw(row.owner(),row.id()),"FAILED","TUTOR_SCOPE_UNAVAILABLE");return; }
            try { checkSources(row); }
            catch(DomainException failure) {
                if(!invalidSource(failure)) throw failure;
                terminal(raw(row.owner(),row.id()),"FAILED","TUTOR_CONTEXT_CHANGED");return;
            }
            validateAnswer(row,answer);
            if(row.mode()==Mode.MODEL && row.dispatched()==null) throw DomainException.conflict("A model answer requires an admitted provider dispatch");
            List<EvidenceView> cited=row.evidence().stream().filter(e->answer.citedChunkIds().contains(e.chunkId())).toList();
            var rules=row.mode()==Mode.MODEL?provider.courseRules(row.evidence()):List.<HttpTutorModelProvider.CourseRule>of();
            var anchors=rules.stream().map(HttpTutorModelProvider.CourseRule::chunkId).collect(java.util.stream.Collectors.toSet());
            var displayedEvidence=row.evidence().stream().filter(card->answer.citedChunkIds().contains(card.chunkId()) || anchors.contains(card.chunkId())).toList();
            String limitation=row.mode()==Mode.MODEL?provider.learnerNote(rules,answer.limitations()):"本地证据整理，未调用语言模型；词项相关性不能代替完整事实核验。 "+answer.limitations();
            UUID message=UUID.randomUUID();
            jdbc.update("INSERT INTO tutor_message(id,request_id,answer,limitations,suggestions,created_at) VALUES(?,?,?,?,'[]',?)",message.toString(),row.id().toString(),answer.text(),limitation,Timestamp.from(clock.instant()));
            for(EvidenceView e:cited) jdbc.update("INSERT INTO message_citation(message_id,release_id,chunk_id,chunk_revision,locator) VALUES(?,?,?,?,?)",message.toString(),row.release().toString(),e.chunkId().toString(),e.revision(),clip(e.sourceSection(),255));
            jdbc.update("UPDATE tutor_request SET status='COMPLETED',error_code=NULL WHERE id=?",row.id().toString());
            jdbc.update("UPDATE tutor_request_runtime SET evidence_refs=?,result_hash=?,lease_until=NULL,completed_at=? WHERE request_id=?",json.write(displayedEvidence),digest,Timestamp.from(clock.instant()),row.id().toString());
            events.append(row.workspace(),"TUTOR_REQUEST_COMPLETED","SERVER",Map.of("requestId",row.id(),"epoch",row.epoch(),"mode",row.mode().name(),"citationCount",cited.size()));
        });
    }
    public void fail(Ticket ticket,String code,Usage usage) {
        if(code==null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw DomainException.invalid("A safe tutor failure code is required");
        tx.executeWithoutResult(status->{
            Stored row=lockWorker(ticket.ownerId(),ticket.id());
            if(row.dispatched()!=null && ticket.token().equals(row.token()) && usage!=null) { settle(row,usage);row=raw(row.owner(),row.id()); }
            if(!sameExecution(row,ticket) || !ACTIVE.contains(row.status())) return;
            terminal(row,code.equals("INSUFFICIENT_EVIDENCE")?"INSUFFICIENT_EVIDENCE":"FAILED",code);
        });
    }
    private void validateAnswer(Stored row,Answer answer) {
        if(answer==null || answer.text()==null || answer.text().isBlank() || answer.text().length()>12000 || answer.limitations()==null || answer.limitations().length()>1500
            || answer.citedChunkIds()==null || answer.citedChunkIds().isEmpty() || answer.citedChunkIds().size()>4 || new HashSet<>(answer.citedChunkIds()).size()!=answer.citedChunkIds().size()
            || answer.citedChunkIds().stream().anyMatch(id->row.evidence().stream().noneMatch(e->e.chunkId().equals(id)))) throw new TutorAnswerProvider.Failure("TUTOR_CITATION_INVALID",answer==null?null:answer.usage());
        if(row.mode()==Mode.MODEL && answer.usage()==null) throw new TutorAnswerProvider.Failure("TUTOR_USAGE_UNKNOWN");
        if(row.mode()==Mode.MODEL && (answer.providerModelId()==null || answer.providerModelId().isBlank() || answer.providerModelId().length()>128)) throw new TutorAnswerProvider.Failure("TUTOR_PROVIDER_FORMAT",answer.usage());
    }
    private void reserve(UUID owner,LocalDate day,long tokens) {
        var rows=jdbc.queryForList("SELECT request_count,reserved_tokens,used_tokens FROM tutor_daily_budget WHERE user_id=? AND budget_date=?",owner.toString(),day);
        if(rows.isEmpty()) {
            jdbc.update("INSERT INTO tutor_daily_budget(user_id,budget_date) VALUES(?,?)",owner.toString(),day);
            rows=jdbc.queryForList("SELECT request_count,reserved_tokens,used_tokens FROM tutor_daily_budget WHERE user_id=? AND budget_date=?",owner.toString(),day);
        }
        var row=rows.get(0);long committed=((Number)row.get("reserved_tokens")).longValue()+((Number)row.get("used_tokens")).longValue();
        if(((Number)row.get("request_count")).intValue()>=settings.dailyRequestLimit() || tokens>settings.dailyTokenLimit()-committed) throw new DomainException("TUTOR_QUOTA_EXCEEDED",429,"The UTC daily tutor request or token allowance is exhausted");
        jdbc.update("UPDATE tutor_daily_budget SET request_count=request_count+1,reserved_tokens=reserved_tokens+? WHERE user_id=? AND budget_date=?",tokens,owner.toString(),day);
    }
    private void settle(Stored row,Usage usage) {
        if(usage.promptTokens()<0 || usage.completionTokens()<0 || usage.totalTokens()<0 || usage.promptTokens()>settings.reservationTokens() || usage.completionTokens()>settings.maxOutputTokens()
            || usage.totalTokens()>settings.reservationTokens() || usage.promptTokens()+usage.completionTokens()!=usage.totalTokens()) throw new TutorAnswerProvider.Failure("TUTOR_USAGE_INVALID");
        if(row.used()!=null) {
            if(row.used()!=usage.totalTokens()) throw DomainException.conflict("Provider usage was already settled with a different value");
            return;
        }
        if(row.reserved()==0 || !Set.of("RESERVED","UNKNOWN").contains(row.billing())) throw DomainException.conflict("No admitted token reservation exists for this dispatch");
        int changed=jdbc.update("UPDATE tutor_daily_budget SET reserved_tokens=reserved_tokens-?,used_tokens=used_tokens+? WHERE user_id=? AND budget_date=? AND reserved_tokens>=?",row.reserved(),usage.totalTokens(),row.owner().toString(),row.budgetDate(),row.reserved());
        if(changed!=1) throw new IllegalStateException("Tutor token reservation invariant failed");
        jdbc.update("UPDATE tutor_request_runtime SET reserved_tokens=0,used_tokens=?,billing_state='SETTLED' WHERE request_id=?",usage.totalTokens(),row.id().toString());
        jdbc.update("UPDATE tutor_request SET usage_metrics=? WHERE id=?",json.write(Map.of("promptTokens",usage.promptTokens(),"completionTokens",usage.completionTokens(),"totalTokens",usage.totalTokens(),"source","PROVIDER_REPORTED")),row.id().toString());
        events.append(row.workspace(),"TUTOR_USAGE_SETTLED","SERVER",Map.of("requestId",row.id(),"totalTokens",usage.totalTokens()));
    }
    private void terminal(Stored row,String status,String code) {
        if(!ACTIVE.contains(row.status())) return;
        if(row.reserved()>0 && row.dispatched()==null) {
            int changed=jdbc.update("UPDATE tutor_daily_budget SET reserved_tokens=reserved_tokens-? WHERE user_id=? AND budget_date=? AND reserved_tokens>=?",row.reserved(),row.owner().toString(),row.budgetDate(),row.reserved());
            if(changed!=1) throw new IllegalStateException("Tutor token release invariant failed");
            jdbc.update("UPDATE tutor_request_runtime SET reserved_tokens=0,billing_state='RELEASED' WHERE request_id=?",row.id().toString());
        } else if(row.reserved()>0) jdbc.update("UPDATE tutor_request_runtime SET billing_state='UNKNOWN' WHERE request_id=?",row.id().toString());
        jdbc.update("UPDATE tutor_request SET status=?,error_code=? WHERE id=?",status,code,row.id().toString());
        jdbc.update("UPDATE tutor_request_runtime SET request_epoch=request_epoch+1,lease_until=NULL,completed_at=? WHERE request_id=?",Timestamp.from(clock.instant()),row.id().toString());
        events.append(row.workspace(),"TUTOR_REQUEST_STOPPED","SERVER",Map.of("requestId",row.id(),"status",status,"errorCode",code,"epoch",row.epoch()+1));
    }
    private void requireLease(Stored row,Ticket ticket) {
        if(!sameExecution(row,ticket) || !ACTIVE.contains(row.status())) throw new DomainException("TUTOR_STALE_EXECUTION",409,"Tutor execution is cancelled or superseded");
        if(!clock.instant().isBefore(row.deadline())) throw new DomainException("TUTOR_DEADLINE_EXCEEDED",409,"Tutor request deadline expired");
        if(row.leaseUntil()==null || !clock.instant().isBefore(row.leaseUntil())) throw new DomainException("TUTOR_LEASE_EXPIRED",409,"Tutor worker lease expired");
        if(!scopeAvailable(row)) throw new DomainException("TUTOR_SCOPE_UNAVAILABLE",409,"Tutor account or workspace is unavailable");
    }
    private static boolean sameExecution(Stored row,Ticket ticket) { return row.id().equals(ticket.id()) && row.owner().equals(ticket.ownerId()) && row.workspace().equals(ticket.workspaceId()) && row.epoch()==ticket.epoch() && ticket.token().equals(row.token()); }
    private void checkSources(Stored row) {
        var context=workspaces.readContext(row.owner(),row.workspace(),row.snapshot());
        if(!row.contextHash().equals(context.contentHash())) throw DomainException.conflict("Tutor snapshot changed");
        requirePublished(row.release());validateEvidence(row,row.evidence());
    }
    private void validateEvidence(Stored row,List<EvidenceView> evidence) {
        if(evidence.size()>4 || evidence.stream().anyMatch(e->!e.releaseId().equals(row.release()))) throw DomainException.invalid("Tutor evidence must stay in the fixed release");
        workspaces.validateReferences(row.owner(),row.workspace(),evidence.stream().map(e->new Reference("KNOWLEDGE_CHUNK",e.chunkId(),e.revision(),e.contentHash())).toList());
    }
    private void requirePublished(UUID release) {
        if(jdbc.queryForList("SELECT id FROM content_release WHERE id=? AND status='PUBLISHED'",release.toString()).isEmpty()) throw DomainException.conflict("The conversation's fixed course release is no longer published");
    }
    private Stored owned(UUID owner,UUID id) {
        Stored row=raw(owner,id);workspaces.requireOwned(owner,row.workspace());return row;
    }
    private Stored raw(UUID owner,UUID id) {
        if(owner==null || id==null) throw DomainException.missing();
        var rows=jdbc.query(SELECT+" WHERE r.id=? AND c.user_id=?",this::requestRow,id.toString(),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }
    private Stored lock(UUID owner,UUID id) {
        Stored preview=owned(owner,id);workspaces.lockOwned(owner,preview.workspace());
        jdbc.queryForList("SELECT id FROM tutor_request WHERE id=? FOR UPDATE",id.toString());
        return owned(owner,id);
    }
    /** System-worker path can close an already disabled scope, never reveal its private content. */
    private Stored lockWorker(UUID owner,UUID id) {
        Stored preview=raw(owner,id);
        if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",String.class,owner.toString()).isEmpty()) throw DomainException.missing();
        if(jdbc.queryForList("SELECT id FROM learning_workspace WHERE id=? AND owner_id=? FOR UPDATE",String.class,preview.workspace().toString(),owner.toString()).isEmpty()) throw DomainException.missing();
        jdbc.queryForList("SELECT id FROM tutor_request WHERE id=? FOR UPDATE",id.toString());
        return raw(owner,id);
    }
    private boolean scopeAvailable(Stored row) {
        return !jdbc.queryForList("SELECT w.id FROM learning_workspace w JOIN app_user u ON u.id=w.owner_id WHERE w.id=? AND w.owner_id=? AND w.status='ACTIVE' AND u.status='ACTIVE'",String.class,row.workspace().toString(),row.owner().toString()).isEmpty();
    }
    private TutorResult view(Stored row) {
        boolean current=true;
        try { checkSources(row); }
        catch(DomainException failure) { if(!invalidSource(failure)) throw failure;current=false; }
        boolean visible=current && row.status().equals("COMPLETED");
        String limitations=!current?"请求的上下文或证据已撤回、更新或不可用，旧答案已隐藏，请选择新快照重新提问。":row.limitations()==null?pendingLimitation(row):row.limitations();
        return new TutorResult(row.id(),row.conversation(),row.workspace(),row.snapshot(),row.mode(),row.status(),row.epoch(),row.question(),row.hintLevel(),visible?row.answer():null,visible?row.evidence():List.of(),List.of(),List.of(),limitations,row.error(),current?"CURRENT":"STALE",new UsageView(row.billing(),row.reserved(),row.used()),row.createdAt(),row.completedAt());
    }
    private static String pendingLimitation(Stored row) {
        if(row.billing().equals("UNKNOWN")) return "外部请求的实际消耗尚未确认，预留额度仍保留；系统不会自动重复调用供应商。";
        if(row.status().equals("INSUFFICIENT_EVIDENCE")) return "当前固定课程没有检索到相关证据，未调用模型或编造答案。";
        return row.mode()==Mode.EVIDENCE_ONLY?"本地证据整理模式，不调用语言模型。":"模型模式仅发送本次明确选择的上下文及相关公开证据；模型讲解仍需核验。";
    }
    private ConversationSummary summary(UUID owner,UUID id) {
        if(id==null || owner==null) throw DomainException.missing();
        var rows=jdbc.query("SELECT c.id,wc.workspace_id,c.release_id,c.title,c.created_at FROM conversation c JOIN workspace_conversation wc ON wc.conversation_id=c.id WHERE c.id=? AND c.user_id=?",this::conversationRow,id.toString(),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        workspaces.requireOwned(owner,rows.get(0).workspaceId());return rows.get(0);
    }
    private ConversationSummary conversationRow(ResultSet rs,int index) throws SQLException {
        return new ConversationSummary(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("release_id")),rs.getString("title"),rs.getTimestamp("created_at").toInstant());
    }
    private Stored requestRow(ResultSet rs,int index) throws SQLException {
        Long used=rs.getObject("used_tokens")==null?null:rs.getLong("used_tokens");
        return new Stored(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("user_id")),UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("release_id")),UUID.fromString(rs.getString("conversation_id")),UUID.fromString(rs.getString("snapshot_id")),Mode.valueOf(rs.getString("mode")),rs.getString("question"),rs.getInt("hint_level"),uuid(rs.getString("attempt_id")),rs.getString("status"),rs.getLong("request_epoch"),uuid(rs.getString("lease_token")),instant(rs,"lease_until"),instant(rs,"deadline_at"),rs.getInt("attempt_count"),rs.getString("context_hash"),List.of(json.read(rs.getString("evidence_refs"),EvidenceView[].class)),rs.getString("provider_fingerprint"),rs.getLong("reserved_tokens"),used,rs.getString("billing_state"),rs.getDate("budget_date").toLocalDate(),instant(rs,"dispatched_at"),rs.getString("result_hash"),rs.getString("error_code"),rs.getString("answer"),rs.getString("limitations"),instant(rs,"created_at"),instant(rs,"completed_at"));
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant(); }
    private static UUID uuid(String value) { return value==null?null:UUID.fromString(value); }
    private static String string(UUID value) { return value==null?null:value.toString(); }
    private static String clip(String value,int limit) { return value.length()<=limit?value:value.substring(0,limit); }
    private static void page(int limit) { if(limit<1 || limit>100) throw DomainException.invalid("Page size must be 1..100"); }
    private static boolean invalidSource(DomainException error) { return Set.of(400,404,409).contains(error.status()); }
}
