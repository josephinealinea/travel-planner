package com.josephinealinea.planner.publish.infra;

import com.josephinealinea.planner.publish.infra.PageStore.Area;
import com.josephinealinea.planner.publish.infra.PageStore.PageFile;
import com.josephinealinea.planner.shared.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every behaviour the publish code relies on from a PageStore, run against
 * each implementation. If the filesystem and R2 ever disagree about one of
 * these, a published page behaves differently locally and in the cloud — and
 * several of them (removal, clearing m/) are privacy properties rather than
 * tidiness.
 *
 * Subclasses hand over a store that is empty at the start of every test.
 */
abstract class PageStoreContract {

    static final String SLUG = "latam";

    /** An empty store, fresh for each test. */
    abstract PageStore store();

    private String read(Area area, String slug, String member, PageFile file) {
        return store().read(area, slug, member, file).orElse(null);
    }

    // ── write and read ──────────────────────────────────────────────────────

    @Test
    void readsBackWhatWasWritten() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "<html>trip</html>");
        store().write(Area.PUBLISHED, SLUG, null, PageFile.DATA, "{\"trip\":1}");
        store().write(Area.PUBLISHED, SLUG, "sam", PageFile.INDEX, "<html>sam</html>");

        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEqualTo("<html>trip</html>");
        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.DATA)).isEqualTo("{\"trip\":1}");
        assertThat(read(Area.PUBLISHED, SLUG, "sam", PageFile.INDEX)).isEqualTo("<html>sam</html>");
    }

    @Test
    void roundTripsUtf8() {
        // Flags, arrows and the escaped line separators the renderer emits.
        String page = "Tallinn → Cusco 🇵🇪 \\u2028 ñ";
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, page);

        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEqualTo(page);
    }

    @Test
    void writingAgainReplacesTheFile() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "first");
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "second");

        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEqualTo("second");
    }

    @Test
    void anUnwrittenFileIsEmptyNotAnError() {
        assertThat(store().read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEmpty();
        assertThat(store().read(Area.PUBLISHED, SLUG, "sam", PageFile.DATA)).isEmpty();
        assertThat(store().read(Area.PENDING, "never-published", null, PageFile.INDEX)).isEmpty();
    }

    @Test
    void theTwoAreasAreSeparate() {
        store().write(Area.PENDING, SLUG, null, PageFile.INDEX, "staged");

        assertThat(store().read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEmpty();
        assertThat(read(Area.PENDING, SLUG, null, PageFile.INDEX)).isEqualTo("staged");
    }

    @Test
    void refusesAnythingThatCouldClimbOutOfItsTrip() {
        assertThatThrownBy(() -> store().write(Area.PUBLISHED, "../etc", null, PageFile.INDEX, "x"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store().write(Area.PUBLISHED, SLUG, "..", PageFile.INDEX, "x"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store().read(Area.PUBLISHED, "Latam/../x", null, PageFile.INDEX))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store().read(Area.PUBLISHED, SLUG, "sam/../../x", PageFile.INDEX))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store().remove(Area.PUBLISHED, ""))
                .isInstanceOf(ApiException.class);
    }

    // ── personal pages ──────────────────────────────────────────────────────

    @Test
    void listsExactlyTheMembersWithAPage() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "trip");
        store().write(Area.PUBLISHED, SLUG, "alex", PageFile.INDEX, "alex");
        store().write(Area.PUBLISHED, SLUG, "alex", PageFile.DATA, "{}");
        store().write(Area.PUBLISHED, SLUG, "sam", PageFile.INDEX, "sam");
        // Data with no page is not a page a link could be offered for.
        store().write(Area.PUBLISHED, SLUG, "kim", PageFile.DATA, "{}");
        // Staged and other trips' pages are not this trip's published ones.
        store().write(Area.PENDING, SLUG, "lee", PageFile.INDEX, "lee");
        store().write(Area.PUBLISHED, "latam-2026", "max", PageFile.INDEX, "max");

        assertThat(store().publishedMemberPages(SLUG)).containsExactlyInAnyOrder("alex", "sam");
    }

    @Test
    void aTripWithNoPersonalPagesListsNone() {
        assertThat(store().publishedMemberPages(SLUG)).isEmpty();

        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "trip");
        assertThat(store().publishedMemberPages(SLUG)).isEmpty();
    }

    @Test
    void clearingMemberPagesLeavesTheTripsOwnPage() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "trip");
        store().write(Area.PUBLISHED, SLUG, null, PageFile.DATA, "{}");
        store().write(Area.PUBLISHED, SLUG, "alex", PageFile.INDEX, "alex");
        store().write(Area.PUBLISHED, SLUG, "sam", PageFile.DATA, "{}");

        store().clearMemberPages(Area.PUBLISHED, SLUG);

        assertThat(store().publishedMemberPages(SLUG)).isEmpty();
        assertThat(store().read(Area.PUBLISHED, SLUG, "alex", PageFile.INDEX)).isEmpty();
        assertThat(store().read(Area.PUBLISHED, SLUG, "sam", PageFile.DATA)).isEmpty();
        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEqualTo("trip");
        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.DATA)).isEqualTo("{}");
    }

    /**
     * The republish sequence the renderer runs: clear m/, then write only the
     * members who still want a page. A member who unticked their box must not
     * keep a page at a URL they believe they turned off.
     */
    @Test
    void republishingWithFewerMembersTakesTheOthersPagesDown() {
        store().write(Area.PUBLISHED, SLUG, "alex", PageFile.INDEX, "alex v1");
        store().write(Area.PUBLISHED, SLUG, "sam", PageFile.INDEX, "sam v1");

        store().clearMemberPages(Area.PUBLISHED, SLUG);
        store().write(Area.PUBLISHED, SLUG, "alex", PageFile.INDEX, "alex v2");

        assertThat(store().publishedMemberPages(SLUG)).containsExactly("alex");
        assertThat(read(Area.PUBLISHED, SLUG, "alex", PageFile.INDEX)).isEqualTo("alex v2");
        assertThat(store().read(Area.PUBLISHED, SLUG, "sam", PageFile.INDEX)).isEmpty();
    }

    @Test
    void clearingMemberPagesOfANeverPublishedTripIsHarmless() {
        store().clearMemberPages(Area.PENDING, SLUG);
        store().clearMemberPages(Area.PUBLISHED, SLUG);

        assertThat(store().publishedMemberPages(SLUG)).isEmpty();
    }

    // ── remove ──────────────────────────────────────────────────────────────

    @Test
    void removingATripTakesEveryPersonalPageWithIt() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "trip");
        store().write(Area.PUBLISHED, SLUG, null, PageFile.DATA, "{}");
        store().write(Area.PUBLISHED, SLUG, "sam", PageFile.INDEX, "sam");
        store().write(Area.PUBLISHED, SLUG, "sam", PageFile.DATA, "{}");

        store().remove(Area.PUBLISHED, SLUG);

        assertThat(store().read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEmpty();
        assertThat(store().read(Area.PUBLISHED, SLUG, null, PageFile.DATA)).isEmpty();
        assertThat(store().read(Area.PUBLISHED, SLUG, "sam", PageFile.INDEX)).isEmpty();
        assertThat(store().publishedMemberPages(SLUG)).isEmpty();
    }

    /** "latam" and "latam-2026" are two trips; removing one is not a prefix sweep. */
    @Test
    void removingATripLeavesATripWhoseSlugStartsTheSame() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "latam");
        store().write(Area.PUBLISHED, "latam-2026", null, PageFile.INDEX, "latam 2026");
        store().write(Area.PUBLISHED, "latam-2026", "sam", PageFile.INDEX, "sam");

        store().remove(Area.PUBLISHED, SLUG);

        assertThat(read(Area.PUBLISHED, "latam-2026", null, PageFile.INDEX)).isEqualTo("latam 2026");
        assertThat(store().publishedMemberPages("latam-2026")).containsExactly("sam");
    }

    @Test
    void removingOneAreaLeavesTheOther() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "live");
        store().write(Area.PENDING, SLUG, null, PageFile.INDEX, "staged");

        store().remove(Area.PENDING, SLUG);

        assertThat(store().read(Area.PENDING, SLUG, null, PageFile.INDEX)).isEmpty();
        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEqualTo("live");
    }

    @Test
    void removingSomethingThatIsNotThereIsHarmless() {
        store().remove(Area.PUBLISHED, SLUG);
        store().remove(Area.PENDING, SLUG);
    }

    // ── promote ─────────────────────────────────────────────────────────────

    @Test
    void promotingWithNothingStagedChangesNothing() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "live");

        assertThat(store().promote(SLUG)).isFalse();

        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEqualTo("live");
    }

    /** Staged data with no page is not a staged page — same test as the filesystem's. */
    @Test
    void promotingNeedsAStagedIndex() {
        store().write(Area.PENDING, SLUG, null, PageFile.DATA, "{}");

        assertThat(store().promote(SLUG)).isFalse();

        assertThat(store().read(Area.PUBLISHED, SLUG, null, PageFile.DATA)).isEmpty();
    }

    @Test
    void promotingMovesTheStagedPageAndItsPersonalPagesLive() {
        store().write(Area.PENDING, SLUG, null, PageFile.INDEX, "staged trip");
        store().write(Area.PENDING, SLUG, null, PageFile.DATA, "{\"staged\":true}");
        store().write(Area.PENDING, SLUG, "sam", PageFile.INDEX, "staged sam");
        store().write(Area.PENDING, SLUG, "sam", PageFile.DATA, "{\"sam\":true}");

        assertThat(store().promote(SLUG)).isTrue();

        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEqualTo("staged trip");
        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.DATA)).isEqualTo("{\"staged\":true}");
        assertThat(read(Area.PUBLISHED, SLUG, "sam", PageFile.INDEX)).isEqualTo("staged sam");
        assertThat(read(Area.PUBLISHED, SLUG, "sam", PageFile.DATA)).isEqualTo("{\"sam\":true}");
        assertThat(store().publishedMemberPages(SLUG)).containsExactly("sam");
    }

    @Test
    void promotingLeavesNothingStaged() {
        store().write(Area.PENDING, SLUG, null, PageFile.INDEX, "staged trip");
        store().write(Area.PENDING, SLUG, "sam", PageFile.INDEX, "staged sam");

        store().promote(SLUG);

        assertThat(store().read(Area.PENDING, SLUG, null, PageFile.INDEX)).isEmpty();
        assertThat(store().read(Area.PENDING, SLUG, "sam", PageFile.INDEX)).isEmpty();
        // Nothing left to promote a second time.
        assertThat(store().promote(SLUG)).isFalse();
    }

    /**
     * The old live page is replaced, not merged into: a file or personal page
     * the staged page does not have must not survive from the previous one.
     */
    @Test
    void promotingReplacesTheLivePageRatherThanMergingIntoIt() {
        store().write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "old trip");
        store().write(Area.PUBLISHED, SLUG, null, PageFile.DATA, "{\"old\":true}");
        store().write(Area.PUBLISHED, SLUG, "alex", PageFile.INDEX, "old alex");
        store().write(Area.PENDING, SLUG, null, PageFile.INDEX, "new trip");
        store().write(Area.PENDING, SLUG, "sam", PageFile.INDEX, "new sam");

        store().promote(SLUG);

        assertThat(read(Area.PUBLISHED, SLUG, null, PageFile.INDEX)).isEqualTo("new trip");
        assertThat(store().read(Area.PUBLISHED, SLUG, null, PageFile.DATA)).isEmpty();
        assertThat(store().read(Area.PUBLISHED, SLUG, "alex", PageFile.INDEX)).isEmpty();
        assertThat(store().publishedMemberPages(SLUG)).containsExactly("sam");
    }

    @Test
    void promotingOneTripLeavesAnother() {
        store().write(Area.PUBLISHED, "latam-2026", null, PageFile.INDEX, "other live");
        store().write(Area.PENDING, "latam-2026", null, PageFile.INDEX, "other staged");
        store().write(Area.PENDING, SLUG, null, PageFile.INDEX, "staged");

        store().promote(SLUG);

        assertThat(read(Area.PUBLISHED, "latam-2026", null, PageFile.INDEX)).isEqualTo("other live");
        assertThat(read(Area.PENDING, "latam-2026", null, PageFile.INDEX)).isEqualTo("other staged");
    }
}
