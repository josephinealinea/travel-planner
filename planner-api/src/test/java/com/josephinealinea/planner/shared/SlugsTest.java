package com.josephinealinea.planner.shared;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlugsTest {

    @Test
    void buildsSlugFromTitle() {
        assertThat(Slugs.of("LATAM Trip 2026")).isEqualTo("latam-trip-2026");
        assertThat(Slugs.of("  Café  &  Crème  ")).isEqualTo("cafe-creme");
        assertThat(Slugs.of("!!!")).isEqualTo("trip");
    }

    @Test
    void appendsSuffixUntilFree() {
        Set<String> taken = Set.of("latam-trip-2026", "latam-trip-2026-2");
        assertThat(Slugs.unique("LATAM Trip 2026", taken::contains)).isEqualTo("latam-trip-2026-3");
    }

    @Test
    void rejectsAnythingThatCouldEscapeTheStorageRoot() {
        // Slugs become filenames, so traversal has to be refused outright.
        assertThatThrownBy(() -> Slugs.requireSafe("../../etc/passwd"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Slugs.requireSafe("..")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Slugs.requireSafe("a/b")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Slugs.requireSafe("")).isInstanceOf(ApiException.class);
        assertThat(Slugs.requireSafe("latam-trip-2026")).isEqualTo("latam-trip-2026");
    }
}
