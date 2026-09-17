package com.josephinealinea.planner.publish.web;

import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Slugs;
import com.josephinealinea.planner.storage.YamlPaths;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the rendered pages with no authentication. These are plain files on
 * disk, so the same directory can be handed to a CDN unchanged — this endpoint
 * is the local equivalent, not a dependency of the published output.
 */
@RestController
public class PublicPageController {

    private final YamlPaths paths;

    public PublicPageController(YamlPaths paths) {
        this.paths = paths;
    }

    @GetMapping("/p/{slug}")
    ResponseEntity<String> page(@PathVariable String slug) {
        return read(slug, "index.html", MediaType.TEXT_HTML);
    }

    @GetMapping("/p/{slug}/")
    ResponseEntity<String> pageWithSlash(@PathVariable String slug) {
        return page(slug);
    }

    @GetMapping("/p/{slug}/trip.json")
    ResponseEntity<String> data(@PathVariable String slug) {
        return read(slug, "trip.json", MediaType.APPLICATION_JSON);
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
        return read(memberDir(slug, member), "index.html", MediaType.TEXT_HTML);
    }

    @GetMapping("/p/{slug}/m/{member}/trip.json")
    ResponseEntity<String> memberData(@PathVariable String slug, @PathVariable String member) {
        return read(memberDir(slug, member), "trip.json", MediaType.APPLICATION_JSON);
    }

    private Path memberDir(String slug, String member) {
        // Both halves go through requireSafe inside; neither can climb out.
        return paths.publishedMemberPage(slug, member);
    }

    private ResponseEntity<String> read(String slug, String filename, MediaType type) {
        // requireSafe rejects anything that could climb out of the publish dir.
        return read(paths.publishedTrip(Slugs.requireSafe(slug)), filename, type);
    }

    private ResponseEntity<String> read(Path dir, String filename, MediaType type) {
        Path file = dir.resolve(filename);
        if (!Files.exists(file)) {
            throw ApiException.notFound("Published trip");
        }
        try {
            return ResponseEntity.ok()
                    .contentType(type)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                    .body(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw ApiException.notFound("Published trip");
        }
    }
}
