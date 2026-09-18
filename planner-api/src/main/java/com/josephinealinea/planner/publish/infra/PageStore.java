package com.josephinealinea.planner.publish.infra;

import java.util.Optional;
import java.util.Set;

/**
 * Where rendered pages live: a directory on disk locally, a private R2 bucket
 * in the cloud. Chosen by app.publish.store, independently of the database
 * flag, so local development keeps writing files.
 *
 * The operations are exactly the ones the publish code already did against the
 * filesystem, and nothing more — which is what keeps a second implementation
 * honest. Every slug and member name goes through Slugs.requireSafe inside the
 * implementation, as it did through YamlPaths, so neither can climb out of its
 * trip.
 *
 * A trip's pages sit in one of two {@link Area}s, and within it at
 * {@code <slug>/<file>} for the trip's own page or
 * {@code <slug>/m/<member>/<file>} for a member's personal one. Nesting is what
 * makes removing a trip remove every personal page with it.
 */
public interface PageStore {

    /**
     * Published is public — everything in it is served as-is. Pending holds a
     * page whose publish request the owner has not decided yet, and is never
     * served except through the members-only preview.
     */
    enum Area { PUBLISHED, PENDING }

    /** The two files a rendered page consists of. */
    enum PageFile {
        INDEX("index.html", "text/html; charset=utf-8"),
        DATA("trip.json", "application/json");

        private final String filename;
        private final String contentType;

        PageFile(String filename, String contentType) {
            this.filename = filename;
            this.contentType = contentType;
        }

        public String filename() { return filename; }
        public String contentType() { return contentType; }
    }

    /**
     * Writes one file, replacing it if present.
     *
     * @param member a personal page's member slug, or null for the trip's own
     */
    void write(Area area, String slug, String member, PageFile file, String contents);

    /** One file's contents, or empty when it has not been written. */
    Optional<String> read(Area area, String slug, String member, PageFile file);

    /**
     * Deletes every personal page under the trip, leaving the trip's own page
     * alone. Every render calls this before writing personal pages — see
     * StaticSiteRenderer.writePersonal for why that is load-bearing.
     */
    void clearMemberPages(Area area, String slug);

    /** Deletes the trip's page and every personal page under it. */
    void remove(Area area, String slug);

    /**
     * Moves an approved staged page — personal pages included — into the
     * published area, replacing whatever was live.
     *
     * The live page is deleted <i>first</i>. A failure part-way therefore
     * leaves no page rather than a half-merged one, which is the safe
     * direction to fail in. Afterwards nothing is left staged.
     *
     * @return false, having changed nothing, when no page is staged
     */
    boolean promote(String slug);

    /**
     * Member slugs with a published personal page, in one storage call.
     *
     * A trip load asks this, and one call per member would be one network
     * round trip per member against R2. Only a member whose index.html exists
     * is included — a link is offered only for a page that is actually there.
     */
    Set<String> publishedMemberPages(String slug);
}
