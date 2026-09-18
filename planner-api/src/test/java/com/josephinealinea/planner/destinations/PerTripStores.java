package com.josephinealinea.planner.destinations;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.storage.YamlPaths;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the five per-trip repository contracts share: a YAML store in a temp
 * directory, and the guard that makes an every-field round trip mean
 * something.
 *
 * It lives beside destinations only because a helper has to live somewhere;
 * nothing in it is about destinations.
 */
public final class PerTripStores {

    private PerTripStores() {}

    /**
     * YAML paths rooted in {@code dir}. Built from a mocked AppProperties
     * rather than its ten-argument constructor, so a new settings group does
     * not have to be threaded through every repository test.
     */
    public static YamlPaths yamlPaths(Path dir) {
        AppProperties props = mock(AppProperties.class);
        when(props.storage()).thenReturn(new AppProperties.Storage(dir.toString()));
        when(props.publish()).thenReturn(new AppProperties.Publish(dir.resolve("published").toString(), null));
        return new YamlPaths(props);
    }

    /**
     * Fails unless every stored field of {@code fixture} is set to something a
     * freshly constructed instance does not have — not null, not zero, not
     * false, not an empty list, not the enum it starts as.
     *
     * This is what turns a round-trip test into a guard: a field added to the
     * domain class later arrives unset in the fixture, this fails, and the
     * fixture — then the mapping — has to be taught about it. Without it a
     * new field would round-trip as null == null and pass while never being
     * stored at all.
     *
     * Static and {@code transient} fields are skipped (the legacy
     * {@code destinationIds} holder is transient precisely because it is never
     * written), as is anything named in {@code unstored}.
     */
    public static void assertEveryStoredFieldIsSet(Object fixture, String... unstored) {
        Object fresh = freshInstanceOf(fixture.getClass());
        Set<String> skipped = Set.of(unstored);
        List<String> unset = new ArrayList<>();
        for (Field field : fixture.getClass().getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
            if (skipped.contains(field.getName())) continue;
            field.setAccessible(true);
            try {
                Object value = field.get(fixture);
                if (value == null || Objects.equals(value, field.get(fresh))) unset.add(field.getName());
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        assertThat(unset)
                .as("fields of %s the fixture leaves at their default — set them, and map them",
                        fixture.getClass().getSimpleName())
                .isEmpty();
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
