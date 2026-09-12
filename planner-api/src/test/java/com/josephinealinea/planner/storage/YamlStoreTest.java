package com.josephinealinea.planner.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.destinations.domain.Destination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class YamlStoreTest {

    private static final TypeReference<List<Destination>> LIST = new TypeReference<>() {};

    private final YamlStore store = new YamlStore();

    private static Destination destination(String id, String name) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setTripId("trip-1");
        destination.setName(name);
        destination.setStartDate(LocalDate.of(2026, 10, 25));
        destination.setEndDate(LocalDate.of(2026, 10, 31));
        destination.setLatitude(-13.53188);
        destination.setLongitude(-71.96701);
        return destination;
    }

    @Test
    void missingFileReadsAsEmptyRatherThanFailing(@TempDir Path dir) {
        assertThat(store.readList(dir.resolve("nope.yml"), LIST)).isEmpty();
        assertThat(store.read(dir.resolve("nope.yml"), Destination.class, null)).isNull();
    }

    @Test
    void roundTripsDatesAndCoordinates(@TempDir Path dir) {
        Path file = dir.resolve("destinations.yml");
        store.write(file, List.of(destination("d1", "Cusco")));

        List<Destination> read = store.readList(file, LIST);
        assertThat(read).hasSize(1);
        assertThat(read.get(0).getName()).isEqualTo("Cusco");
        assertThat(read.get(0).getStartDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(read.get(0).nights()).isEqualTo(6L);
        assertThat(read.get(0).getLatitude()).isEqualTo(-13.53188);
    }

    @Test
    void writesDatesAsReadableIsoStringsNotEpochNumbers(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("destinations.yml");
        store.write(file, List.of(destination("d1", "Cusco")));

        String yaml = Files.readString(file);
        // These files sit next to hand-written ones, so they have to stay legible.
        assertThat(yaml).contains("2026-10-25");
        assertThat(yaml).doesNotContain("---");
        // NON_NULL inclusion keeps unset optional fields out of the file.
        assertThat(yaml).doesNotContain("notes");
    }

    @Test
    void writeLeavesNoTemporaryFileBehind(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("destinations.yml");
        store.write(file, List.of(destination("d1", "Cusco")));

        try (var entries = Files.list(dir)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .containsExactly("destinations.yml");
        }
    }

    @Test
    void concurrentReadModifyWriteUnderTheLockKeepsEveryRecord(@TempDir Path dir) throws Exception {
        TripLocks locks = new TripLocks();
        Path file = dir.resolve("destinations.yml");
        int writers = 12;

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            IntStream.range(0, writers).forEach(i -> pool.submit(() -> {
                start.await();
                locks.writeVoid("trip-1", () -> {
                    var current = new java.util.ArrayList<>(store.readList(file, LIST));
                    current.add(destination("d" + i, "Stop " + i));
                    store.write(file, current);
                });
                return null;
            }));
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Without the lock these interleaved read-modify-writes would lose records.
        assertThat(store.readList(file, LIST)).hasSize(writers);
    }

    @Test
    void deleteTreeRemovesAPublishedDirectory(@TempDir Path dir) {
        Path published = dir.resolve("published").resolve("latam-trip-2026");
        store.writeText(published.resolve("index.html"), "<h1>hi</h1>");
        store.writeText(published.resolve("trip.json"), "{}");

        store.deleteTree(published);
        assertThat(Files.exists(published)).isFalse();
    }
}
