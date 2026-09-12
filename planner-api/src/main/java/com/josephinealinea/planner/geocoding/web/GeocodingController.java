package com.josephinealinea.planner.geocoding.web;

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

    private final GeocodingClient geocoding;

    public GeocodingController(GeocodingClient geocoding) {
        this.geocoding = geocoding;
    }

    /** Autocomplete for the destination name field. An empty list is normal. */
    @GetMapping
    List<PlaceSuggestion> search(@RequestParam("q") String query) {
        return geocoding.search(query);
    }
}
