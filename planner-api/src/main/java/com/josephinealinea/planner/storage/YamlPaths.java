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

    /** Global, not per trip: exchange rates are a property of the day. */
    public Path rates() { return root.resolve("rates.yml"); }

    public Path outbox() { return root.resolve("outbox"); }

    public Path publishDir() { return publishDir; }

    /**
     * Where a page waits while its publish request is undecided.
     *
     * A sibling of the publish directory rather than a subdirectory of it, so
     * that directory keeps meaning exactly one thing: everything in it is
     * public. Copy it to a CDN and you cannot accidentally ship a page the
     * owner has not approved.
     */
    public Path pendingDir() {
        return publishDir.resolveSibling(publishDir.getFileName() + "-pending");
    }

    public Path pendingTrip(String slug) {
        return pendingDir().resolve(Slugs.requireSafe(slug));
    }

    public Path publishedTrip(String slug) {
        return publishDir.resolve(Slugs.requireSafe(slug));
    }

    /**
     * One member's own page, inside the trip's directory rather than beside it.
     *
     * A sibling `<slug>-<member>` would have been prettier and is a trap: slugs
     * are derived from trip titles, so a trip called "LATAM" and a trip called
     * "LATAM 2026" produce "latam" and "latam-2026", and any cleanup that swept
     * `latam-*` would delete the second trip's whole page. Nesting means there
     * is nothing to sweep — removing the trip's directory removes every
     * personal page with it, which is the cascade a published page has to have
     * (see TripService.delete in CLAUDE.md).
     */
    public Path publishedMemberPage(String slug, String memberSlug) {
        return publishedTrip(slug).resolve("m").resolve(Slugs.requireSafe(memberSlug));
    }

    public Path pendingMemberPage(String slug, String memberSlug) {
        return pendingTrip(slug).resolve("m").resolve(Slugs.requireSafe(memberSlug));
    }

    public Path trip(String slug)         { return travels("trip", slug); }
    public Path destinations(String slug) { return travels("destinations", slug); }
    public Path checklist(String slug)    { return travels("checklist", slug); }
    public Path itinerary(String slug)    { return travels("itinerary", slug); }
    public Path budget(String slug)       { return travels("budget", slug); }
    public Path weather(String slug)      { return travels("weather", slug); }

    private Path travels(String entity, String slug) {
        return root.resolve("travels").resolve(entity).resolve(Slugs.requireSafe(slug) + ".yml");
    }
}
