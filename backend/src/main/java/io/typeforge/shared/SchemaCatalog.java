package io.typeforge.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Only bundled schemas can be selected. User-provided schemas/remote refs are never accepted. */
@Component
public final class SchemaCatalog {
    private final ObjectMapper mapper;
    private final JsonNode components;
    private final Map<String,JsonSchema> schemas=new ConcurrentHashMap<>();
    private final JsonSchemaFactory factory=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    public SchemaCatalog(ObjectMapper mapper) throws IOException {
        this.mapper=mapper;
        try(var input=new ClassPathResource("contracts/openapi.json").getInputStream()) { components=mapper.readTree(input).path("components"); }
        scanReferences(components);
    }
    private void scanReferences(JsonNode node) {
        if(node.isObject() && node.has("$ref") && !node.path("$ref").asText().startsWith("#/components/schemas/")) throw new IllegalStateException("Only bundled local schema references are allowed");
        if(node.isContainerNode()) node.forEach(this::scanReferences);
    }
    /** A private copy for structured model decoding, from the same bundled contract used on admission. */
    public ObjectNode definition(String name) {
        if(!(components.path("schemas").path(name) instanceof ObjectNode schema)) throw new IllegalArgumentException("Unknown schema name");
        return schema.deepCopy();
    }
    public void validate(String name,Object value) {
        if(!components.path("schemas").has(name)) throw new IllegalArgumentException("Unknown schema name");
        JsonNode data=mapper.valueToTree(value);
        if(data.toString().length()>262144) throw DomainException.invalid("Structured content exceeds 256 KiB character limit");
        JsonSchema schema=schemas.computeIfAbsent(name,key->{
            ObjectNode root=mapper.createObjectNode();
            root.put("$schema","https://json-schema.org/draft/2020-12/schema");
            root.put("$ref","#/components/schemas/"+key);root.set("components",components);
            return factory.getSchema(root,SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        });
        if(!schema.validate(data).isEmpty()) throw DomainException.invalid("Input violates "+name+" schema");
    }
}
