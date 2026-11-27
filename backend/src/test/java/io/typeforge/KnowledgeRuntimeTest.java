package io.typeforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual migrated SQL, immutable release membership, retrieval and public answer boundaries. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:typeforge-knowledge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Transactional
class KnowledgeRuntimeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JsonCodec json;
    @Autowired Clock clock;
    @Autowired CurriculumService curriculum;
    @Autowired LocalKnowledgeSearch search;
    @Autowired ApplicationContext context;
    private DemoContentInitializer initializer;
    private final UUID release = DemoContentInitializer.RELEASE_ID;

    @BeforeEach void loadExplicitDemoFixtureWithinRollbackTransaction() {
        initializer = new DemoContentInitializer(jdbc, tx, json, clock);
        initializer.initialize();
    }

    @Test void classroomEditionAddsDistinctGradedOpportunitiesForEveryConceptWithoutChangingPreviousRelease() {
        assertEquals(12, curriculum.exercises(release, null, null, 100).items().size());
        initializer.initializeClassroomEdition();
        initializer.initializeClassroomEdition();
        var current = DemoContentInitializer.CLASSROOM_RELEASE_ID;
        assertEquals(current, curriculum.activeRelease());
        assertEquals(12, curriculum.exercises(release, null, null, 100).items().size());
        assertEquals(33, curriculum.exercises(current, null, null, 100).items().size());
        for (var concept : curriculum.concepts(current)) {
            assertTrue(concept.relatedExerciseIds().size() >= 2);
            assertEquals(curriculum.exercises(current, concept.id(), null, 100).items().stream()
                    .map(CurriculumService.ExerciseView::id).limit(20).toList(), concept.relatedExerciseIds());
        }
    }

    @Test void peerPackMatchesAllPublishedConceptsAndTheOriginalRetrievalChecks() throws Exception {
        var concepts = curriculum.concepts(release);
        assertTrue(concepts.stream().allMatch(c -> c.companion() != null && !c.companion().examples().isEmpty()));
        var originals = concepts.stream().flatMap(c -> c.companion().examples().stream()).distinct().toList();
        assertEquals(16, originals.size());
        assertEquals(16, originals.stream().map(e -> e.id()).distinct().count());
        assertTrue(originals.stream().allMatch(e -> json.sha256(e.code()).equals(e.sha256())));
        var reviews = concepts.stream().flatMap(c -> c.companion().reviews().stream()).toList();
        assertEquals(5, reviews.size());
        assertEquals(2, reviews.stream().filter(r -> r.correction() != null).count());
        var reader = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var input = getClass().getResourceAsStream("/course/peer-search-20261118.json")) {
            var queries = reader.readTree(input);
            assertEquals(13, queries.size());
            for (var q : queries) {
                var expected = q.get("expectedPrimaryConcept").asText();
                var result = search.search(release, q.get("query").asText(), null, 5);
                if (expected.isEmpty()) {
                    assertTrue(result.insufficientEvidence() && result.items().isEmpty(), q.get("id").asText());
                } else {
                    var found = result.items().get(0).conceptId();
                    assertEquals(expected, concepts.stream().filter(c -> c.id().equals(found)).findFirst().orElseThrow().code());
                }
            }
        }
    }

    @Test void demoInitializerIsOptInAndCreatesNoPretendStudentsOrHumanReview() {
        assertTrue(context.getBeansOfType(DemoContentInitializer.class).isEmpty());
        assertEquals("DISABLED", jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", String.class, DemoContentInitializer.AUTHOR_ID.toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM exercise_attempt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM learning_event", Integer.class));
        var audit = json.tree(jdbc.queryForObject("SELECT safe_metadata FROM audit_event WHERE action='DEMO_CONTENT_INITIALIZED'", Object.class));
        assertFalse(audit.path("humanReview").asBoolean(true));
        assertEquals(DemoContentInitializer.ORIGIN, audit.path("origin").asText());
    }

    @Test void publishedSqlContentHasSixteenConceptsCardsAndTwelveExercises() {
        assertEquals(release, curriculum.activeRelease());
        var concepts = curriculum.concepts(release);
        assertEquals(16, concepts.size());
        assertEquals(16, curriculum.chunks(release, List.of()).size());
        assertEquals(12, curriculum.exercises(release, null, null, 100).items().size());
        assertTrue(concepts.stream().allMatch(c -> !c.evidence().isEmpty()));
        assertTrue(concepts.stream().flatMap(c -> c.evidence().stream()).allMatch(e -> e.contentHash().matches("[a-f0-9]{64}") && e.source().url().startsWith("https://docs.oracle.com/")));
    }

    @Test void selectedLongLessonRetainsItsEntireVerifiedBodyWithoutExpandingSearchOrDirectory() {
        var card = curriculum.chunks(release, List.of()).get(0);
        String body = "学".repeat(2999) + "📚" + "结尾仍是本节课程正文";
        jdbc.update("UPDATE knowledge_chunk SET body=?,content_hash=? WHERE id=? AND revision=?",
            body, json.sha256(body), card.chunkId().toString(), card.revision());
        var selected = curriculum.concept(release, card.conceptId()).evidence().stream()
            .filter(e -> e.chunkId().equals(card.chunkId())).findFirst().orElseThrow();
        assertEquals("学".repeat(2999), selected.excerpt());
        assertEquals(body, selected.fullText());
        assertEquals(json.sha256(body), selected.contentHash());
        var preview = curriculum.concepts(release).stream().filter(c -> c.id().equals(card.conceptId()))
            .findFirst().orElseThrow().evidence().stream().filter(e -> e.chunkId().equals(card.chunkId())).findFirst().orElseThrow();
        assertEquals(selected.excerpt(), preview.excerpt());
        assertFalse(json.tree(json.write(preview)).has("fullText"));
        assertFalse(json.tree(json.write(CurriculumService.evidence(curriculum.chunks(release, List.of(card.conceptId()))
            .stream().filter(c -> c.chunkId().equals(card.chunkId())).findFirst().orElseThrow(), 1))).has("fullText"));
    }

    @Test void reinitializationDoesNotDuplicateOrOverwritePublishedFixtures() {
        initializer.initialize();
        assertEquals(16, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
        assertEquals(12, jdbc.queryForObject("SELECT COUNT(*) FROM exercise", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='DEMO_CONTENT_INITIALIZED'", Integer.class));
        jdbc.update("UPDATE content_release SET manifest_hash=? WHERE id=?", "0".repeat(64), release.toString());
        assertThrows(IllegalStateException.class, initializer::initialize);
    }

    @Test void exerciseResponseDoesNotLeakAnswerSpecificationOrNestedOptionSecrets() {
        var example = curriculum.exercises(release, null, null, 1).items().get(0);
        var answer = (ObjectNode) json.tree(jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=?", Object.class, example.id().toString(), example.revision()));
        ((ObjectNode) answer.path("options").get(0)).put("hiddenCorrectness", true);
        answer.put("hiddenTests", "SECRET-TEST-CONTENT");
        jdbc.update("UPDATE exercise SET answer_spec=? WHERE id=? AND revision=?", json.write(answer), example.id().toString(), example.revision());
        var serialized = json.tree(json.write(curriculum.exercise(release, example.id(), example.revision())));
        assertEquals(4, serialized.path("options").size());
        for (String key : List.of("answer_spec", "answerSpec", "optionIds", "correctOptionIds", "explanation", "misconceptions", "hiddenTests")) assertFalse(serialized.has(key));
        assertFalse(serialized.path("options").get(0).has("hiddenCorrectness"));
        assertFalse(serialized.toString().contains("SECRET-TEST-CONTENT"));
    }

    @Test void exercisePaginationHasNoDuplicatesOrSkippedItems() {
        var seen = new ArrayList<UUID>();
        String cursor = null;
        do {
            var page = curriculum.exercises(release, null, cursor, 5);
            seen.addAll(page.items().stream().map(CurriculumService.ExerciseView::id).toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(12, seen.size());
        assertEquals(12, new HashSet<>(seen).size());
        assertThrows(DomainException.class, () -> curriculum.exercises(release, null, "not-a-cursor", 5));
    }

    @Test void lexicalRetrievalHasRealScoresAndReturnsNoMatchWithoutFabricatingEvidence() {
        var result = search.search(release, "PECS producer consumer", null, 5);
        assertFalse(result.insufficientEvidence());
        assertTrue(result.items().stream().allMatch(e -> e.rankScore() > 0 && e.releaseId().equals(release)));
        assertEquals(LocalKnowledgeSearch.INDEX_VERSION, result.indexVersion());
        assertEquals(result, search.search(release, "PECS producer consumer", null, 5));
        var missing = search.search(release, "unobtainiumQuantumZebra9487", null, 5);
        assertTrue(missing.items().isEmpty());
        assertTrue(missing.insufficientEvidence());
        assertTrue(search.search(release, "!!!", null, 5).items().isEmpty());
    }

    @Test void englishIdentifiersRemainSearchableWhenAdjacentToChineseWithoutWhitespace() {
        for(String term:List.of("PECS","Number","List","SafeVarargs")) {
            assertFalse(search.search(release,term,null,5).items().isEmpty(),"Mixed-script corpus must preserve standalone identifier: "+term);
        }
        var pecs=curriculum.concepts(release).stream().filter(c->c.code().equals("G12")).findFirst().orElseThrow();
        var upper=search.search(release,"PECS",null,5);
        assertEquals(pecs.id(),upper.items().get(0).conceptId());
        assertEquals(upper,search.search(release,"pecs",null,5));
    }

    @Test void chineseBigramQueriesAndConceptFilterUseOnlyMatchingPublishedCards() {
        var concept = curriculum.concepts(release).stream().filter(c -> c.code().equals("G13")).findFirst().orElseThrow();
        var result = search.search(release, "通配符捕获", concept.id(), 8);
        assertEquals(1, result.items().size());
        assertEquals(concept.id(), result.items().get(0).conceptId());
        assertTrue(result.items().get(0).excerpt().contains("helper"));
        assertTrue(search.search(release, "通配符捕获", UUID.randomUUID(), 8).items().isEmpty());
    }

    @Test void releaseFilterCannotSeeAnotherReleasesCardsOrExercises() {
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO content_release(id,version,status,manifest_hash,created_by) VALUES(?,?,?,?,?)",
            other.toString(), "empty-" + other.toString().substring(0, 20), "PUBLISHED", json.hash(other), DemoContentInitializer.AUTHOR_ID.toString());
        assertTrue(curriculum.concepts(other).isEmpty());
        assertTrue(search.search(other, "generics", null, 5).items().isEmpty());
        var example = curriculum.exercises(release, null, null, 1).items().get(0);
        assertThrows(DomainException.class, () -> curriculum.exercise(other, example.id(), example.revision()));
        assertThrows(DomainException.class, () -> curriculum.exercise(release, example.id(), example.revision() + 1));
        jdbc.update("UPDATE content_release SET status='DRAFT' WHERE id=?", other.toString());
        assertThrows(DomainException.class, () -> search.search(other, "generics", null, 5));
    }

    @Test void tamperedPublishedBodyFailsIntegrityInsteadOfReturningStaleHashEvidence() {
        var card = curriculum.chunks(release, List.of()).get(0);
        assertNotEquals(json.sha256("tampered"), card.contentHash());
        jdbc.update("UPDATE knowledge_chunk SET body='tampered' WHERE id=? AND revision=?", card.chunkId().toString(), card.revision());
        DomainException error = assertThrows(DomainException.class, () -> search.search(release, "generics", null, 5));
        assertEquals("CONTENT_INTEGRITY_FAILURE", error.code());
    }

    @Test void invalidQueryAndLimitAreRejectedAtTheServiceBoundary() {
        assertThrows(DomainException.class, () -> search.search(release, " ", null, 5));
        assertThrows(DomainException.class, () -> search.search(release, "a".repeat(501), null, 5));
        assertThrows(DomainException.class, () -> search.search(release, "generics", null, 9));
        assertThrows(DomainException.class, () -> curriculum.exercises(release, null, null, 0));
    }
}
