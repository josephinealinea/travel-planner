package com.josephinealinea.planner.storage;

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
 * The guard that makes a storage round-trip test mean something.
 *
 * Saving an object, reloading it and comparing the two proves only that the
 * fields the fixture happened to set survive. A field left at its default —
 * null, false, zero, an empty list — comes back at its default whether or not
 * the store kept it, so a column nobody mapped passes silently. This fails
 * instead: every stored field of the fixture must differ from a freshly
 * constructed instance, so the round trip genuinely exercises it.
 *
 * It is also what keeps the stores honest as the domain grows. Add a field to
 * a domain class and every round-trip test for it fails here until the fixture
 * sets it — which is the moment to map it in the JDBC repository too, rather
 * than finding out in production that it was never written.
 *
 * Static and transient fields are skipped (neither is stored), and so are
 * fields the caller names as deliberately unstored — each with a reason at the
 * call site.
 */
public final class EveryField {

    private EveryField() {}

    /**
     * Asserts that no stored field of {@code fixture} is left as a fresh
     * instance would have it.
     *
     * @param fresh    makes the comparison instance — usually the no-arg constructor
     * @param excluded fields deliberately not stored, each justified by the caller
     */
    public static <T> void assertEverySet(T fixture, Supplier<T> fresh, Set<String> excluded) {
        T defaults = fresh.get();
        List<String> unset = new ArrayList<>();
        // Walks the superclasses too, so a field inherited from a shared base
        // is held to the same rule.
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

    /**
     * The same, comparing against the class's own no-arg constructor — which
     * every stored domain class has, since both Jackson and the row mappers
     * need one.
     */
    public static void assertEverySet(Object fixture, String... excluded) {
        assertEverySet(fixture, () -> freshInstanceOf(fixture.getClass()), Set.of(excluded));
    }

    private static Object freshInstanceOf(Class<?> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("needs a no-arg constructor: " + type, e);
        }
    }
}
