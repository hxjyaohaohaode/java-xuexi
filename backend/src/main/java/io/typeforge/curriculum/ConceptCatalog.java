package io.typeforge.curriculum;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface ConceptCatalog {
    record ConceptView(UUID id, String code, String title, UUID releaseId, List<UUID> prerequisites) {
        public ConceptView { prerequisites = List.copyOf(prerequisites); }
    }
    Optional<ConceptView> find(UUID conceptId, UUID releaseId);
}
