package io.typeforge.tutor;

import io.typeforge.workspace.WorkspaceContextStore.Reference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolves a run's immutable, owner-validated storyboard at publication time. */
public interface CoursewareSlideSource {
    record Card(String key,String title,String kind,String body,String prompt,Reference evidence) {}
    record Edition(String title,List<Card> cards) {}
    Optional<Edition> edition(UUID owner,UUID workspace,UUID run);
}
