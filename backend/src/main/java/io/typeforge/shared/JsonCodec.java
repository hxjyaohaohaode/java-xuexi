package io.typeforge.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Canonical object-key ordering; array order remains meaningful. Hashes are integrity, not authority. */
@Component
public final class JsonCodec {
    private final ObjectMapper mapper;
    public JsonCodec(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(canonical(mapper.valueToTree(value))); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot encode structured value", e); }
    }
    public <T> T read(String value, Class<T> type) {
        try {
            JsonNode node = tree(value);
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot decode structured value", e); }
    }
    public JsonNode tree(String value) {
        try {
            JsonNode result = mapper.readTree(value);
            // H2's JSON SQL type can return a JSON string for JDBC string parameters.
            if (result != null && result.isTextual()) {
                String nested = result.textValue();
                if (nested.startsWith("{") || nested.startsWith("[")) result = mapper.readTree(nested);
            }
            return result;
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot decode JSON", e); }
    }
    public JsonNode tree(Object value) {
        if (value instanceof byte[] bytes) return tree(new String(bytes, StandardCharsets.UTF_8));
        return tree(String.valueOf(value));
    }
    public String hash(Object value) { return sha256(write(value)); }
    public String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            names.forEach(name -> copy.set(name, canonical(node.get(name))));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(item -> copy.add(canonical(item)));
            return copy;
        }
        return node;
    }
}
