package io.typeforge.shared;
import java.util.List;
public record Page<T>(List<T> items, String nextCursor, int limit) {
    public Page {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        items = List.copyOf(items);
        if (items.size() > limit) throw new IllegalArgumentException("items exceed limit");
    }
}
