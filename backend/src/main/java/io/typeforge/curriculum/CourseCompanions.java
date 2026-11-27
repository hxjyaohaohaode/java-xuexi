package io.typeforge.curriculum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.typeforge.shared.JsonCodec;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** An independently versioned teaching supplement, attached only to matching published concept IDs. */
@Component
public final class CourseCompanions {
    public record Example(String id, String title, List<UUID> conceptIds, String code, String sha256,
                          String section, String sourceUrl, boolean compiles, String stdout, String warning) {}
    public record PeerReview(String id, UUID conceptId, String question, String criterion, String correction) {}
    public record Supplement(String version, List<Example> examples, List<PeerReview> reviews) {}

    private final Supplement course;

    public CourseCompanions(ObjectMapper mapper, JsonCodec json) throws IOException {
        try (var input = new ClassPathResource("knowledge/peer-course-20261118.json").getInputStream()) {
            course = mapper.readValue(input, Supplement.class);
        }
        for (var example : course.examples()) {
            if (!json.sha256(example.code()).equals(example.sha256())) {
                throw new IllegalStateException("Course example differs from its recorded source: " + example.id());
            }
        }
    }

    public Supplement forConcept(UUID id) {
        var examples = course.examples().stream().filter(example -> example.conceptIds().contains(id)).toList();
        var reviews = course.reviews().stream().filter(review -> review.conceptId().equals(id)).toList();
        return examples.isEmpty() && reviews.isEmpty() ? null : new Supplement(course.version(), examples, reviews);
    }
}
