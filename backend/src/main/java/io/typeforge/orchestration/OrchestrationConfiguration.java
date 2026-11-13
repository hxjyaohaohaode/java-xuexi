package io.typeforge.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Role authorization comes from the same versioned registry used by the contracts. */
@Configuration
public class OrchestrationConfiguration {
    @Bean
    PlanValidator planValidator(ObjectMapper mapper) throws IOException {
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        try (var input = new ClassPathResource("agent/roles.json").getInputStream()) {
            JsonNode root = mapper.readTree(input);
            if (!root.isArray() || root.isEmpty()) {
                throw new IllegalStateException("role registry must be a nonempty array");
            }
            for (JsonNode role : root) {
                Set<String> admitted = new LinkedHashSet<>();
                role.path("allowedTools").forEach(tool -> admitted.add(tool.asText()));
                if (roles.putIfAbsent(role.path("id").asText(), Set.copyOf(admitted)) != null) {
                    throw new IllegalStateException("duplicate role registration");
                }
            }
        }
        return new PlanValidator(roles);
    }
}
