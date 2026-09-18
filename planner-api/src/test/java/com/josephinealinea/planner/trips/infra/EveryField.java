package com.josephinealinea.planner.trips.infra;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of an every-field round-trip test that keeps it honest over time.
 *
 * A round trip compared with {@code usingRecursiveComparison()} only proves
 * the fields the fixture sets. A field added to a domain class later is left
 * at its default in the fixture, compares equal to the default that an
 * unmapped column reads back as, and the test passes while the store quietly
 * drops it. So the fixture itself is checked first: <b>every field must hold
 * something other than what a freshly constructed object holds</b> — not
 * null, not the initialiser ({@code "EUR"}, {@code DRAFT}), not {@code false},
 * not an empty collection. A new field then fails here until the fixture sets
 * it, and setting it makes the round trip prove it is stored.
 *
 * Fields that are {@code static} or {@code transient} are skipped, and any
 * field a store deliberately does not keep is named in {@code excluded} — in
 * the test, where the reason can sit next to it.
 */
public final class EveryField {

    private EveryField() {}

    public static <T> void assertEverySet(T fixture, Supplier<T> fresh, Set<String> excluded) {
        T defaults = fresh.get();
        List<String> unset = new ArrayList<>();
        for (Class<?> type = fixture.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
                if (field.isSynthetic() || excluded.contains(field.getName())) continue;
                field.setAccessible(true);
                try {
                    Object value = field.get(fixture);
                    Object initial = field.get(defaults);
                    if (value == null
                            || Objects.equals(value, initial)
                            || (value instanceof Collection<?> c && c.isEmpty())
                            || (value instanceof Map<?, ?> m && m.isEmpty())) {
                        unset.add(type.getSimpleName() + "." + field.getName());
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        assertThat(unset)
                .as("fields the fixture leaves at their default — set them, so the round trip "
                        + "proves they are stored, or exclude them with a reason")
                .isEmpty();
    }
}
