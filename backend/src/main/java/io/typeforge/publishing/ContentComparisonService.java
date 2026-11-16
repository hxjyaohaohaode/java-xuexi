package io.typeforge.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.typeforge.identity.ContentAccess;
import io.typeforge.operations.RuntimeCapabilityProvider;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Authorized comparisons include private grading rules; never exposed through learner DTOs. */
@Service
public final class ContentComparisonService implements RuntimeCapabilityProvider {
    public record Change(String path,String kind,JsonNode before,JsonNode after) {}
    public record Comparison(String kind,UUID id,int fromRevision,int toRevision,String fromReviewHash,String toReviewHash,
                             String fromStatus,String toStatus,List<Change> changes) {}
    private final ContentDraftService drafts;
    private final ContentAccess access;
    private final JsonCodec json;
    public ContentComparisonService(ContentDraftService drafts,ContentAccess access,JsonCodec json) {this.drafts=drafts;this.access=access;this.json=json;}
    @Override public Set<String> enabledCapabilities() {return Set.of("CONTENT_REVISION_DIFF");}
    public Comparison compare(UUID actor,String kind,UUID id,int from,int to) {
        access.requireEditor(actor);
        if(id==null || from<1 || to<1 || from==to || !Set.of("CHUNK","EXERCISE").contains(kind==null?"":kind)) throw DomainException.invalid("请选择同一内容的两个不同有效修订。");
        Object left=kind.equals("CHUNK")?drafts.chunk(actor,id,from):drafts.exercise(actor,id,from);
        Object right=kind.equals("CHUNK")?drafts.chunk(actor,id,to):drafts.exercise(actor,id,to);
        ObjectNode before=(ObjectNode)json.tree(json.write(left)),after=(ObjectNode)json.tree(json.write(right));
        String fromHash=before.path("reviewHash").asText(),toHash=after.path("reviewHash").asText();
        String fromStatus=before.path("status").asText(),toStatus=after.path("status").asText();
        // Revision, review status and authorship are metadata, not content edits. Hashes remain visible in the header.
        var metadata=List.of("id","revision","status","authorId","createdAt","reviewHash","contentHash");
        before.remove(metadata);after.remove(metadata);
        var changes=new ArrayList<Change>();diff("",before,after,changes,0);
        var result=new Comparison(kind,id,from,to,fromHash,toHash,fromStatus,toStatus,List.copyOf(changes));
        if(json.write(result).getBytes(StandardCharsets.UTF_8).length>1_048_576) throw new DomainException("COMPARISON_TOO_LARGE",422,"修订差异超过显示上限，请分别查看原始修订。");
        return result;
    }
    private static void diff(String path,JsonNode before,JsonNode after,List<Change> changes,int depth) {
        if(java.util.Objects.equals(before,after)) return;
        if(depth>24 || changes.size()>=256) throw new DomainException("COMPARISON_TOO_COMPLEX",422,"修订差异结构超过比较上限。");
        if(before!=null && after!=null && before.isObject() && after.isObject()) {
            var names=new TreeSet<String>();before.fieldNames().forEachRemaining(names::add);after.fieldNames().forEachRemaining(names::add);
            for(String name:names) diff(path+"/"+name.replace("~","~0").replace("/","~1"),before.get(name),after.get(name),changes,depth+1);
        } else changes.add(new Change(path,before==null?"ADDED":after==null?"REMOVED":"CHANGED",before==null?NullNode.getInstance():before,after==null?NullNode.getInstance():after));
    }
}
