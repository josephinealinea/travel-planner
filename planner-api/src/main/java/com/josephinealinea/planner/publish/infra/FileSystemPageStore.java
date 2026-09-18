package com.josephinealinea.planner.publish.infra;

import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pages as plain files under app.publish.dir, with the pending area as its
 * sibling directory — see YamlPaths.pendingDir. The default, and what local
 * development uses.
 *
 * This is the publish code's filesystem handling moved here unchanged: the same
 * paths, the same atomic YamlStore writes, the same delete-then-move promote.
 * The published directory stays a plain static site that can be copied to a
 * CDN as-is.
 */
@Component
@ConditionalOnProperty(name = "app.publish.store", havingValue = "filesystem", matchIfMissing = true)
public class FileSystemPageStore implements PageStore {

    private final YamlStore store;
    private final YamlPaths paths;

    public FileSystemPageStore(YamlStore store, YamlPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    @Override
    public void write(Area area, String slug, String member, PageFile file, String contents) {
        store.writeText(dir(area, slug, member).resolve(file.filename()), contents);
    }

    @Override
    public Optional<String> read(Area area, String slug, String member, PageFile file) {
        Path path = dir(area, slug, member).resolve(file.filename());
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    @Override
    public void clearMemberPages(Area area, String slug) {
        store.deleteTree(trip(area, slug).resolve("m"));
    }

    @Override
    public void remove(Area area, String slug) {
        store.deleteTree(trip(area, slug));
    }

    @Override
    public boolean promote(String slug) {
        var staged = paths.pendingTrip(slug);
        if (!Files.exists(staged.resolve(PageFile.INDEX.filename()))) return false;

        var live = paths.publishedTrip(slug);
        // Files.move refuses to replace a non-empty directory, so the old page
        // goes first. A crash between the two leaves no page rather than a
        // half-merged one, which is the safe direction to fail in.
        store.deleteTree(live);
        try {
            Files.createDirectories(live.getParent());
            Files.move(staged, live);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not publish " + slug, e);
        }
        return true;
    }

    @Override
    public Set<String> publishedMemberPages(String slug) {
        Path members = paths.publishedTrip(slug).resolve("m");
        if (!Files.isDirectory(members)) return Set.of();
        try (var entries = Files.list(members)) {
            Set<String> found = new TreeSet<>();
            entries.filter(dir -> Files.exists(dir.resolve(PageFile.INDEX.filename())))
                    .forEach(dir -> found.add(dir.getFileName().toString()));
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + members, e);
        }
    }

    private Path trip(Area area, String slug) {
        return area == Area.PUBLISHED ? paths.publishedTrip(slug) : paths.pendingTrip(slug);
    }

    /** Both halves go through Slugs.requireSafe inside YamlPaths. */
    private Path dir(Area area, String slug, String member) {
        if (member == null) return trip(area, slug);
        return area == Area.PUBLISHED
                ? paths.publishedMemberPage(slug, member)
                : paths.pendingMemberPage(slug, member);
    }
}
