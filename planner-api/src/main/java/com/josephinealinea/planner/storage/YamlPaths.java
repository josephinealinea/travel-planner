package com.josephinealinea.planner.storage;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.shared.Slugs;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Every path in the file store, in one place. The per-trip layout deliberately
 * mirrors the Jekyll site's _data/travels/&lt;entity&gt;/&lt;trip-key&gt;.yml so the files
 * stay recognisable next to the hand-written ones.
 */
@Component
public class YamlPaths {

    private final Path root;
    private final Path publishDir;

    public YamlPaths(AppProperties props) {
        this.root = Paths.get(props.storage().root()).toAbsolutePath().normalize();
        this.publishDir = Paths.get(props.publish().dir()).toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    public Path users() { return root.resolve("users.yml"); }

    public Path tripIndex() { return root.resolve("trips").resolve("index.yml"); }

    public Path secret() { return root.resolve("secret.yml"); }

    public Path outbox() { return root.resolve("outbox"); }

    public Path publishDir() { return publishDir; }

    public Path publishedTrip(String slug) {
        return publishDir.resolve(Slugs.requireSafe(slug));
    }

    public Path trip(String slug)         { return travels("trip", slug); }
    public Path destinations(String slug) { return travels("destinations", slug); }
    public Path checklist(String slug)    { return travels("checklist", slug); }
    public Path itinerary(String slug)    { return travels("itinerary", slug); }
    public Path budget(String slug)       { return travels("budget", slug); }

    private Path travels(String entity, String slug) {
        return root.resolve("travels").resolve(entity).resolve(Slugs.requireSafe(slug) + ".yml");
    }
}
