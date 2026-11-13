package io.typeforge.tutor;

import io.typeforge.orchestration.AgentTool;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Typed local lexical retrieval; versioned source evidence is validated before it leaves the tool. */
@Component
public final class LocalSearchTool implements AgentTool<LocalSearchTool.Input,LocalKnowledgeSearch.SearchResult> {
    public record Input(String query,UUID releaseId,int topK) {}
    private final WorkspaceService workspaces;
    private final LocalKnowledgeSearch search;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final SchemaCatalog schemas;
    private final Clock clock;
    public LocalSearchTool(WorkspaceService workspaces,LocalKnowledgeSearch search,JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,SchemaCatalog schemas,Clock clock) {
        this.workspaces=workspaces;this.search=search;this.jdbc=jdbc;this.tx=tx;this.json=json;this.schemas=schemas;this.clock=clock;
    }
    @Override public String name() { return "searchEvidence"; }
    @Override public String inputSchemaId() { return "ToolSearchInput"; }
    @Override public String outputSchemaId() { return "SearchResult"; }
    @Override public LocalKnowledgeSearch.SearchResult execute(Input input,AgentTool.Context context) {
        schemas.validate(inputSchemaId(),input);
        return tx.execute(status->{
            var admission=LocalContextTool.admit(name(),input,context,workspaces,jdbc,json,clock);
            if(!admission.workspace().releaseId().equals(input.releaseId())) throw DomainException.invalid("Tool retrieval must use the workspace's fixed release");
            var result=search.search(input.releaseId(),input.query(),null,input.topK());
            schemas.validate(outputSchemaId(),result);
            return result;
        });
    }
}
