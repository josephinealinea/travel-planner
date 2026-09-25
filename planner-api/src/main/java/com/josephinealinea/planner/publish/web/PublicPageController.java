package com.josephinealinea.planner.publish.web;

import com.josephinealinea.planner.publish.infra.PageStore;
import com.josephinealinea.planner.publish.infra.PageStore.Area;
import com.josephinealinea.planner.publish.infra.PageStore.PageFile;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Slugs;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the rendered pages with no authentication. These are plain files on
 * disk, so the same directory can be handed to a CDN unchanged — this endpoint
 * is the local equivalent, not a dependency of the published output.
 *
 * In the cloud the pages live in R2 and planner-web/functions/p/[[path]].js
 * serves them instead, without waking the API. That Function mirrors this
 * class route for route, so a change here is a change there.
 */
@RestController
public class PublicPageController {

    private final PageStore pages;

    public PublicPageController(PageStore pages) {
        this.pages = pages;
    }

    @GetMapping("/p/{slug}")
    ResponseEntity<String> page(@PathVariable String slug) {
        return read(slug, null, PageFile.INDEX, MediaType.TEXT_HTML);
    }

    @GetMapping("/p/{slug}/")
    ResponseEntity<String> pageWithSlash(@PathVariable String slug) {
        return page(slug);
    }

    @GetMapping("/p/{slug}/trip.json")
    ResponseEntity<String> data(@PathVariable String slug) {
        return read(slug, null, PageFile.DATA, MediaType.APPLICATION_JSON);
    }

    /**
     * One member's own page: the same trip, with their share of each expense
     * in place of the trip's whole spend.
     *
     * No authentication here either, and there could not be: a published page
     * is a static file, so whose budget it shows is decided when it is written
     * rather than when it is read. Which is also why another member's figures
     * are not in this file at all — see StaticSiteRenderer.write.
     */
    @GetMapping({"/p/{slug}/m/{member}", "/p/{slug}/m/{member}/"})
    ResponseEntity<String> memberPage(@PathVariable String slug, @PathVariable String member) {
        return read(slug, member, PageFile.INDEX, MediaType.TEXT_HTML);
    }

    @GetMapping("/p/{slug}/m/{member}/trip.json")
    ResponseEntity<String> memberData(@PathVariable String slug, @PathVariable String member) {
        return read(slug, member, PageFile.DATA, MediaType.APPLICATION_JSON);
    }

    /**
     * Only ever the published area: a staged page is reachable through the
     * members-only preview and nowhere else.
     */
    private ResponseEntity<String> read(String slug, String member, PageFile file, MediaType type) {
        // requireSafe rejects anything that could climb out of the publish
        // dir; the store checks the member half, as YamlPaths always did.
        String body = pages.read(Area.PUBLISHED, Slugs.requireSafe(slug), member, file)
                .orElseThrow(() -> ApiException.notFound("error.publishedTrip.notFound"));
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(body);
    }
}
