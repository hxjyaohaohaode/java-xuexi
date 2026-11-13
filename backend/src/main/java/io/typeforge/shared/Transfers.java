package io.typeforge.shared;
import java.util.Collection;
public final class Transfers {
    private Transfers() {}
    /** A small real PECS example; the destination must support addAll. */
    public static <T> void copy(Collection<? extends T> source, Collection<? super T> destination) {
        destination.addAll(source);
    }
}
