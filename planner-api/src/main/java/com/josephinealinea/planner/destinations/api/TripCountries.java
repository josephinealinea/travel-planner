package com.josephinealinea.planner.destinations.api;

import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The countries a trip actually goes to, and the one place that decides whether
 * a country code may be linked to anything on it.
 *
 * Checklist items, itinerary entries and budget rows all link to countries
 * rather than to cities. A country is what every consumer of those links
 * wanted anyway — a chip, a filter, a group-by, the budget's country
 * breakdown — and storing the city id only to resolve it to a country on every
 * read was precision that existed to be thrown away. It also took a whole
 * class of unlinking with it: removing Cusco no longer touches a Peru-linked
 * item, because the trip still goes to Peru via Ollantaytambo.
 *
 * The codes on offer are always derived from the trip's own destinations, so
 * nothing can be filed under a country the trip does not visit.
 */
@Service
public class TripCountries {

    private final DestinationRepository destinations;

    public TripCountries(DestinationRepository destinations) {
        this.destinations = destinations;
    }

    /**
     * Every country this trip visits, once each, in the order its destinations
     * are listed. A destination with no country contributes nothing — some
     * places genuinely have no gazetteer entry, and those items simply carry
     * no country.
     */
    public List<String> of(Trip trip) {
        List<String> codes = new ArrayList<>();
        for (Destination destination : destinations.findAllOrdered(trip.getSlug())) {
            String code = normalise(destination.getCountryCode());
            if (code != null && !codes.contains(code)) codes.add(code);
        }
        return codes;
    }

    /**
     * Validates codes against the trip, de-duplicated, order preserved.
     *
     * A code the trip does not visit is a client error rather than something to
     * drop quietly: it means the caller is working from a stale list of
     * destinations, and silently saving nothing would look like success.
     */
    public List<String> validate(Trip trip, List<String> countryCodes) {
        if (countryCodes == null || countryCodes.isEmpty()) return new ArrayList<>();

        List<String> available = of(trip);
        List<String> valid = new ArrayList<>();
        for (String raw : countryCodes) {
            String code = normalise(raw);
            if (code == null) continue;
            if (!available.contains(code)) {
                throw ApiException.badRequest("error.trip.noDestinationIn", code);
            }
            if (!valid.contains(code)) valid.add(code);
        }
        return valid;
    }

    /** The country of one destination, for the seeder. Null when it has none. */
    public String codeOf(Destination destination) {
        return destination == null ? null : normalise(destination.getCountryCode());
    }

    private static String normalise(String code) {
        if (code == null || code.isBlank()) return null;
        return code.trim().toUpperCase();
    }
}
