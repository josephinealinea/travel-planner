package com.josephinealinea.planner.news.api;

import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryTable;
import com.josephinealinea.planner.news.CurrentsClient;
import com.josephinealinea.planner.news.NewsDataClient;
import com.josephinealinea.planner.news.NewsProperties;
import com.josephinealinea.planner.news.domain.DestinationNewsGroup;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Below the destinations list: a place-relevant story or two per unique
 * destination, no server-side storage. See the design spec
 * (.claude/specs/2026-09-27-llama-lookout-news-design.md) for why the query is
 * always a quoted place name plus the country name, why there is no merging
 * across providers, and why a capped provider makes no call at all.
 */
@Service
public class NewsService {

    private final DestinationRepository destinations;
    private final TripAccessService access;
    private final NewsDataClient newsData;
    private final CurrentsClient currents;
    private final NewsProperties props;
    private final ApiUsageService usage;
    private final CircuitBreaker breaker;
    private final Random random;

    @Autowired // two constructors: Spring must be told which one is real
    public NewsService(DestinationRepository destinations, TripAccessService access, NewsDataClient newsData,
                       CurrentsClient currents, NewsProperties props, ApiUsageService usage, CircuitBreaker breaker) {
        this(destinations, access, newsData, currents, props, usage, breaker, new Random());
    }

    NewsService(DestinationRepository destinations, TripAccessService access, NewsDataClient newsData,
               CurrentsClient currents, NewsProperties props, ApiUsageService usage, CircuitBreaker breaker,
               Random random) {
        this.destinations = destinations;
        this.access = access;
        this.newsData = newsData;
        this.currents = currents;
        this.props = props;
        this.usage = usage;
        this.breaker = breaker;
        this.random = random;
    }

    public List<DestinationNewsGroup> newsFor(String tripId, String userId, String languageCode) {
        if (!props.enabled()) return List.of();

        Trip trip = access.requireMember(tripId, userId);
        List<Destination> ordered = destinations.findAllOrdered(trip.getSlug());

        Map<String, Destination> unique = new LinkedHashMap<>();
        for (Destination d : ordered) unique.putIfAbsent(dedupeKey(d), d);

        List<DestinationNewsGroup> groups = new ArrayList<>();
        boolean first = true;
        for (Destination destination : unique.values()) {
            List<NewsArticle> articles = lookup(queryFor(destination), languageCode, first);
            first = false;
            if (!articles.isEmpty()) {
                groups.add(new DestinationNewsGroup(destination.getName(), destination.getCountryCode(),
                        destination.getCountryFlag(), articles));
            }
        }
        return groups;
    }

    private static String dedupeKey(Destination d) {
        String name = d.getName() == null ? "" : d.getName().trim().toLowerCase(Locale.ROOT);
        String code = d.getCountryCode() == null ? "" : d.getCountryCode().trim().toUpperCase(Locale.ROOT);
        return name + "|" + code;
    }

    private static String queryFor(Destination d) {
        String name = d.getName() == null ? "" : d.getName().trim();
        String country = CountryTable.nameOf(d.getCountryCode());
        return country == null ? "\"" + name + "\"" : "\"" + name + "\" " + country;
    }

    /** The first lookup in the batch always tries NewsData; every later one is
     * a coin flip. Either way, a capped or breaker-paused provider is swapped
     * for the other one before it is ever asked, and if both are unavailable
     * the destination gets no call at all. */
    private List<NewsArticle> lookup(String query, String languageCode, boolean preferNewsData) {
        boolean tryNewsDataFirst = preferNewsData || random.nextBoolean();
        String chosen = tryNewsDataFirst ? NewsDataClient.SERVICE : CurrentsClient.SERVICE;
        String other = tryNewsDataFirst ? CurrentsClient.SERVICE : NewsDataClient.SERVICE;
        if (capped(chosen)) chosen = other;
        if (capped(chosen)) return List.of(); // both providers are spent for today

        int limit = props.maxArticlesPerDestination();
        return chosen.equals(NewsDataClient.SERVICE)
                ? newsData.search(query, languageCode, limit)
                : currents.search(query, languageCode, limit);
    }

    /** Capped for the day, or the breaker has it paused after repeated failures — either way, the
     * caller must not spend this provider's turn on it and should try the other one instead. */
    private boolean capped(String service) {
        NewsProperties.Service config = service.equals(NewsDataClient.SERVICE) ? props.newsdata() : props.currents();
        return !config.enabled() || usage.callsToday(service) >= props.capFor(config) || breaker.paused(service);
    }
}
