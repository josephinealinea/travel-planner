package com.josephinealinea.planner.geocoding.web;

import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.geocoding.GeocodingClient;
import com.josephinealinea.planner.geocoding.PlaceSuggestion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/geocode")
public class GeocodingController {

    /** What a country picker needs and nothing else. */
    public record CountryOption(String code, String name, String flag) {}

    private final GeocodingClient geocoding;
    private final CountryCatalog countries;

    public GeocodingController(GeocodingClient geocoding, CountryCatalog countries) {
        this.geocoding = geocoding;
        this.countries = countries;
    }

    /** Autocomplete for the destination name field. An empty list is normal. */
    @GetMapping
    List<PlaceSuggestion> search(@RequestParam("q") String query) {
        return geocoding.search(query);
    }

    /**
     * Every country, for the destination form's picker. Sorted by name, which
     * is the order it is chosen in; the code rides along so the form never has
     * to ask anyone to remember that Peru is PE.
     */
    @GetMapping("/countries")
    List<CountryOption> countries() {
        return countries.all().stream()
                .map(country -> new CountryOption(
                        country.alpha2Code().toUpperCase(), country.name(), country.flag()))
                .toList();
    }
}
