package com.josephinealinea.planner.news.api;

import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.news.CurrentsClient;
import com.josephinealinea.planner.news.NewsDataClient;
import com.josephinealinea.planner.news.NewsProperties;
import com.josephinealinea.planner.news.domain.DestinationNewsGroup;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewsServiceTest {

    private static Destination destination(String name, String code, String flag) {
        Destination d = new Destination();
        d.setId(name);
        d.setName(name);
        d.setCountryCode(code);
        d.setCountryFlag(flag);
        return d;
    }

    private static NewsArticle article(String title) {
        return new NewsArticle(title, "desc", "https://x/" + title, null, "Source", null);
    }

    /** A real, never-paused breaker — most tests don't care about it. */
    private static CircuitBreaker freshBreaker() {
        return new CircuitBreaker(CircuitBreakerProperties.defaults(), Clock.systemUTC());
    }

    /** Deterministic stand-in for the coin flip: every non-forced pick goes to Currents. */
    private static Random alwaysCurrents() {
        return new Random() {
            @Override
            public boolean nextBoolean() {
                return false;
            }
        };
    }

    @Test
    void queriesEachUniqueDestinationOnceAndSkipsDuplicates() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);

        Trip trip = new Trip();
        trip.setSlug("peru-2026");
        when(access.requireMember("trip-1", "user-1")).thenReturn(trip);
        when(destinations.findAllOrdered("peru-2026")).thenReturn(List.of(
                destination("Cusco", "PE", "🇵🇪"),
                destination("cusco", "pe", "🇵🇪"), // same place, different case
                destination("La Paz", "BO", "🇧🇴")));
        when(usage.callsToday(anyString())).thenReturn(0);
        // Cusco is the very first lookup, so it always tries NewsData regardless
        // of the coin flip. La Paz is the second unique destination: with
        // alwaysCurrents() the coin flip deterministically picks Currents.
        when(newsData.search(eq("\"Cusco\" Peru"), eq("en"), eq(3))).thenReturn(List.of(article("Cusco story")));
        when(currents.search(eq("\"La Paz\" Bolivia"), eq("en"), eq(3))).thenReturn(List.of(article("La Paz story")));

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage,
                freshBreaker(), alwaysCurrents());
        List<DestinationNewsGroup> groups = service.newsFor("trip-1", "user-1", "en");

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).destinationName()).isEqualTo("Cusco");
        assertThat(groups.get(0).articles()).extracting(NewsArticle::title).containsExactly("Cusco story");
        assertThat(groups.get(1).destinationName()).isEqualTo("La Paz");
        assertThat(groups.get(1).articles()).extracting(NewsArticle::title).containsExactly("La Paz story");
        // Cusco is the very first lookup: it must have gone to NewsData, never Currents.
        verify(newsData, times(1)).search(eq("\"Cusco\" Peru"), anyString(), anyInt());
        verify(currents, never()).search(eq("\"Cusco\" Peru"), anyString(), anyInt());
        // La Paz is the second lookup: with alwaysCurrents() it must go to Currents, never NewsData.
        verify(currents, times(1)).search(eq("\"La Paz\" Bolivia"), anyString(), anyInt());
        verify(newsData, never()).search(eq("\"La Paz\" Bolivia"), anyString(), anyInt());
    }

    @Test
    void aDestinationWithNoCountryCodeQueriesTheBareName() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(destination("Somewhere", null, null)));
        when(usage.callsToday(anyString())).thenReturn(0);
        when(newsData.search(eq("\"Somewhere\""), anyString(), anyInt())).thenReturn(List.of(article("A")));

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage,
                freshBreaker(), new Random(1));
        assertThat(service.newsFor("t", "u", "en")).hasSize(1);
        verify(newsData).search(eq("\"Somewhere\""), eq("en"), eq(3));
    }

    /** A country code CountryTable doesn't recognise behaves exactly like no code at all: the
     * bare quoted name, never an exception and never the literal string "null" in the query. */
    @Test
    void anUnrecognisedCountryCodeQueriesTheBareNameNotTheWordNull() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(destination("Nowhere", "ZZ", null)));
        when(usage.callsToday(anyString())).thenReturn(0);
        when(newsData.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage,
                freshBreaker(), new Random(1));
        assertThat(service.newsFor("t", "u", "en")).isEmpty();
        verify(newsData).search(eq("\"Nowhere\""), eq("en"), eq(3));
    }

    /** Two destinations with the same name in different countries (e.g. two "Santiago"s) must be
     * queried separately, never collapsed into one lookup by name alone. */
    @Test
    void sameNameDifferentCountriesAreQueriedSeparately() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(
                destination("Santiago", "CL", "🇨🇱"),
                destination("Santiago", "ES", "🇪🇸")));
        when(usage.callsToday(anyString())).thenReturn(0);
        when(newsData.search(eq("\"Santiago\" Chile"), anyString(), anyInt())).thenReturn(List.of(article("Chile story")));
        when(currents.search(eq("\"Santiago\" Spain"), anyString(), anyInt())).thenReturn(List.of(article("Spain story")));

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage,
                freshBreaker(), alwaysCurrents());
        List<DestinationNewsGroup> groups = service.newsFor("t", "u", "en");

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).countryCode()).isEqualTo("CL");
        assertThat(groups.get(1).countryCode()).isEqualTo("ES");
        verify(newsData).search(eq("\"Santiago\" Chile"), anyString(), anyInt());
        verify(currents).search(eq("\"Santiago\" Spain"), anyString(), anyInt());
    }

    @Test
    void aDestinationWithNoArticlesFromEitherProviderIsAbsent() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(destination("Nowhere", "ZZ", null)));
        when(usage.callsToday(anyString())).thenReturn(0);
        when(newsData.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage,
                freshBreaker(), new Random(1));
        assertThat(service.newsFor("t", "u", "en")).isEmpty();
    }

    @Test
    void bothProvidersAtTheirCapMakesNoOutboundCallAtAll() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 1.0, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 1),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 1));
        ApiUsageService usage = mock(ApiUsageService.class);
        when(usage.callsToday(NewsDataClient.SERVICE)).thenReturn(1); // already at cap 1
        when(usage.callsToday(CurrentsClient.SERVICE)).thenReturn(1);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(destination("Cusco", "PE", "🇵🇪")));

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage,
                freshBreaker(), new Random(1));
        assertThat(service.newsFor("t", "u", "en")).isEmpty();
        verifyNoInteractions(newsData, currents);
    }

    /** A provider the breaker has paused (repeated outages) must be treated exactly like a capped
     * one: the other provider gets every lookup, and the paused one is never called. */
    @Test
    void aPausedProviderFallsBackToTheOtherOneJustLikeACappedOne() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);
        when(usage.callsToday(anyString())).thenReturn(0);
        CircuitBreaker breaker = mock(CircuitBreaker.class);
        when(breaker.paused(NewsDataClient.SERVICE)).thenReturn(true);
        when(breaker.paused(CurrentsClient.SERVICE)).thenReturn(false);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(destination("Cusco", "PE", "🇵🇪")));
        when(currents.search(eq("\"Cusco\" Peru"), anyString(), anyInt())).thenReturn(List.of(article("A")));

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage, breaker, new Random(1));
        assertThat(service.newsFor("t", "u", "en")).hasSize(1);
        verify(currents).search(eq("\"Cusco\" Peru"), anyString(), anyInt());
        verifyNoInteractions(newsData);
    }

    @Test
    void newsIsEmptyOutrightWhenTheFeatureIsDisabled() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(false, 0.9, 3, null, null);

        NewsService service = new NewsService(destinations, access, newsData, currents, props,
                mock(ApiUsageService.class), freshBreaker(), new Random(1));
        assertThat(service.newsFor("t", "u", "en")).isEmpty();
        verifyNoInteractions(destinations, access, newsData, currents);
    }
}
