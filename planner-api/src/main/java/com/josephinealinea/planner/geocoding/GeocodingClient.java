package com.josephinealinea.planner.geocoding;

import com.josephinealinea.planner.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Place lookup against countries.dev. Three behaviours here are not obvious and
 * were all confirmed against the live API:
 *
 *  1. /cities answers 404 with the plain text "No cities found" when nothing
 *     matches. That is an empty result, not a failure.
 *  2. /cities has a population floor. Uyuni (10,293) and Ollantaytambo (2,000)
 *     are missing from it but present in /places — and small towns are exactly
 *     what a trip is made of. So both are queried and merged.
 *  3. /places is the full gazetteer, so it also returns administrative regions
 *     ("Departamento de Cusco"), hotels and rivers. Only featureClass "P",
 *     a populated place, is kept.
 *
 * Some places have no entry under the name travellers use at all — Peru's Aguas
 * Calientes is registered as Machupicchu — so an empty result is a normal
 * outcome and the caller falls back to manual coordinates.
 *
 * That same place is why /places is also asked for the query with its spaces
 * removed: see placeTerms.
 */
@Service
public class GeocodingClient {

    private static final Logger log = LoggerFactory.getLogger(GeocodingClient.class);
    private static final String POPULATED_PLACE = "P";

    private final RestClient client;
    private final CountryCatalog countries;
    private final int cityLimit;
    private final int placeLimit;

    public GeocodingClient(RestClient countriesDevClient, CountryCatalog countries, AppProperties props) {
        this.client = countriesDevClient;
        this.countries = countries;
        this.cityLimit = props.geocoding().cityLimit();
        this.placeLimit = props.geocoding().placeLimit();
    }

    public List<PlaceSuggestion> search(String query) {
        if (query == null || query.trim().length() < 2) return List.of();
        String q = query.trim();

        // Keyed by geonameId so the same place found by both endpoints appears once.
        Map<Long, CountriesDevDtos.Place> merged = new LinkedHashMap<>();
        for (CountriesDevDtos.Place place : cities(q)) {
            if (place.geonameId() != null) merged.putIfAbsent(place.geonameId(), place);
        }
        for (String term : placeTerms(q)) {
            for (CountriesDevDtos.Place place : places(term)) {
                if (place.geonameId() != null) merged.putIfAbsent(place.geonameId(), place);
            }
        }

        return merged.values().stream()
                .filter(place -> place.latitude() != null && place.longitude() != null)
                .sorted(Comparator.comparing(
                        (CountriesDevDtos.Place place) -> place.population() == null ? 0L : place.population())
                        .reversed())
                .limit(cityLimit)
                .map(this::enrich)
                .toList();
    }

    /** Cleanly ranked, but only larger settlements. */
    private List<CountriesDevDtos.Place> cities(String query) {
        return fetch("/cities", query, cityLimit, null);
    }

    /**
     * The query as typed, and — when it has spaces — the same thing without
     * them.
     *
     * The gazetteer stores some two-word places as one word, and matching is
     * not forgiving about it: "Machu Picchu" returns a research base in
     * Antarctica and a hamlet in Bolivia, while the Peruvian town it means is
     * filed as "Machupicchu" and never appears. Asking for both spellings and
     * merging on geonameId is what makes the obvious thing to type work.
     *
     * Only /places gets the second term. A place that needs this trick is a
     * small one — Machupicchu has 4,446 people — and /places is the endpoint
     * without the population floor, so /cities would have nothing to add.
     */
    private static List<String> placeTerms(String query) {
        String squashed = query.replaceAll("\\s+", "");
        return squashed.equalsIgnoreCase(query) ? List.of(query) : List.of(query, squashed);
    }

    /** The full gazetteer, filtered down to populated places. */
    private List<CountriesDevDtos.Place> places(String query) {
        return fetch("/places", query, placeLimit, POPULATED_PLACE);
    }

    private List<CountriesDevDtos.Place> fetch(String path, String query, int limit, String featureClass) {
        try {
            CountriesDevDtos.Place[] response = client.get()
                    .uri(uriBuilder -> uriBuilder.path(path)
                            .queryParam("q", query)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    // "No cities found" arrives as a 404 with a text body; treat
                    // it as no matches rather than letting it surface as a 500.
                    .onStatus(status -> status.value() == 404, (req, res) -> {})
                    .body(CountriesDevDtos.Place[].class);

            if (response == null) return List.of();
            List<CountriesDevDtos.Place> results = new ArrayList<>();
            for (CountriesDevDtos.Place place : response) {
                if (featureClass == null || featureClass.equals(place.featureClass())) {
                    results.add(place);
                }
            }
            return results;
        } catch (Exception e) {
            // A lookup outage must not block adding a destination by hand.
            log.warn("countries.dev {} lookup failed for \"{}\": {}", path, query, e.getMessage());
            return List.of();
        }
    }

    private PlaceSuggestion enrich(CountriesDevDtos.Place place) {
        String code = place.countryCode();
        return new PlaceSuggestion(
                place.geonameId(),
                place.name(),
                code,
                countries.nameOf(code),
                countries.flagOf(code),
                countries.currencyOf(code),
                place.latitude(),
                place.longitude(),
                place.population(),
                place.timezone());
    }
}
