package com.josephinealinea.planner.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the YAML documents that make up the file store. Two
 * properties matter:
 *
 * <ul>
 *   <li>a missing file reads as empty rather than as an error, so a brand-new
 *       install and a trip with no budget behave the same way;</li>
 *   <li>writes go to a sibling .tmp and are then moved into place, so a crash
 *       mid-write can never leave a half-serialised trip on disk.</li>
 * </ul>
 *
 * Callers are responsible for holding the right {@link TripLocks} lock.
 */
@Component
public class YamlStore {

    private final ObjectMapper mapper;

    public YamlStore() {
        this.mapper = YAMLMapper.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .build()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // These files can be hand-edited and will gain fields over
                // time; an unknown key should never make a trip unreadable.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /** Reused when rendering the published trip.json. */
    public ObjectMapper mapper() {
        return mapper;
    }

    public <T> T read(Path file, Class<T> type, T fallback) {
        if (!Files.exists(file)) return fallback;
        try {
            T value = mapper.readValue(file.toFile(), type);
            return value == null ? fallback : value;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    public <T> List<T> readList(Path file, TypeReference<List<T>> type) {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<T> value = mapper.readValue(file.toFile(), type);
            return value == null ? new ArrayList<>() : new ArrayList<>(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    public void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), value);
            moveIntoPlace(tmp, file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    public void writeText(Path file, String contents) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, contents);
            moveIntoPlace(tmp, file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    public void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + file, e);
        }
    }

    /** Recursive delete, used when a trip or a published page goes away. */
    public void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not delete " + path, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + dir, e);
        }
    }

    private void moveIntoPlace(Path tmp, Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems (and some Docker volume drivers) refuse an atomic
            // move; a plain replace is still better than writing in place.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
