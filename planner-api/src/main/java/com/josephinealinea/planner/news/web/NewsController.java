package com.josephinealinea.planner.news.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.news.api.NewsService;
import com.josephinealinea.planner.news.domain.DestinationNewsGroup;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Member-only: below the Destinations list. No server-side cache — the
 * browser's own HTTP cache is the only one, via the header below. See the
 * design spec for why.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/news")
public class NewsController {

    private final NewsService news;
    private final CurrentUserContext currentUser;

    public NewsController(NewsService news, CurrentUserContext currentUser) {
        this.news = news;
        this.currentUser = currentUser;
    }

    @GetMapping
    ResponseEntity<List<DestinationNewsGroup>> news(@PathVariable String tripId) {
        String language = LocaleContextHolder.getLocale().getLanguage();
        List<DestinationNewsGroup> groups = news.newsFor(tripId, currentUser.userId(), language);
        // An empty answer is never cached for a day: it could be a brief outage that has since
        // recovered (both providers capped, paused or erroring), and the browser cache is the
        // only cache there is, with no other way for a member to force a refresh.
        CacheControl cacheControl = groups.isEmpty()
                ? CacheControl.noCache()
                : CacheControl.maxAge(Duration.ofHours(24)).cachePrivate();
        return ResponseEntity.ok().cacheControl(cacheControl).body(groups);
    }
}
