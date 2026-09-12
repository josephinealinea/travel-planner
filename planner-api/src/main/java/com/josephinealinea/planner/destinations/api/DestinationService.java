package com.josephinealinea.planner.destinations.api;

import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class DestinationService {

    /** What the caller has to say to create or edit a destination. */
    public record Input(
            String name,
            String countryCode,
            String countryName,
            Double latitude,
            Double longitude,
            Long geonameId,
            String timezone,
            LocalDate startDate,
            LocalDate endDate,
            String notes) {}

    /** Creating one also produces its three checklist items. */
    public record Created(Destination destination, List<ChecklistItem> seededChecklist) {}

    private final DestinationRepository destinations;
    private final ChecklistRepository checklist;
    private final ChecklistSeeder seeder;
    private final TripAccessService access;
    private final CountryCatalog countries;

    public DestinationService(DestinationRepository destinations,
                              ChecklistRepository checklist,
                              ChecklistSeeder seeder,
                              TripAccessService access,
                              CountryCatalog countries) {
        this.destinations = destinations;
        this.checklist = checklist;
        this.seeder = seeder;
        this.access = access;
        this.countries = countries;
    }

    public List<Destination> list(String tripId, String userId) {
        Trip trip = access.requireMember(tripId, userId);
        return destinations.findAllOrdered(trip.getSlug());
    }

    public Created create(String tripId, String userId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        requireName(input.name());
        requireDateOrder(input.startDate(), input.endDate());

        Destination destination = new Destination();
        destination.setId(Ids.newId());
        destination.setTripId(tripId);
        destination.setCreatedAt(Instant.now());
        destination.setSortOrder(destinations.findAll(trip.getSlug()).size());
        apply(destination, input);
        destinations.save(trip.getSlug(), destination);

        int nextSortOrder = checklist.findAll(trip.getSlug()).size();
        List<ChecklistItem> seeded = seeder.seedFor(destination, nextSortOrder);
        checklist.saveAll(trip.getSlug(), seeded);

        return new Created(destination, seeded);
    }

    public Destination update(String tripId, String userId, String destinationId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        Destination destination = destinations.findById(trip.getSlug(), destinationId)
                .orElseThrow(() -> ApiException.notFound("Destination"));

        if (input.name() != null) requireName(input.name());
        LocalDate start = input.startDate() != null ? input.startDate() : destination.getStartDate();
        LocalDate end = input.endDate() != null ? input.endDate() : destination.getEndDate();
        requireDateOrder(start, end);

        apply(destination, input);
        // Deliberately does not rewrite the seeded checklist text — once created
        // those descriptions belong to whoever has been editing them.
        return destinations.save(trip.getSlug(), destination);
    }

    /**
     * Deleting a destination unlinks its checklist items rather than deleting
     * them. Losing a place should never silently discard the planning done
     * against it, including any plans and costs hanging off those items.
     */
    public int delete(String tripId, String userId, String destinationId) {
        Trip trip = access.requireMember(tripId, userId);
        Destination destination = destinations.findById(trip.getSlug(), destinationId)
                .orElseThrow(() -> ApiException.notFound("Destination"));

        destinations.delete(trip.getSlug(), destinationId);

        List<ChecklistItem> items = new ArrayList<>(checklist.findAll(trip.getSlug()));
        int unlinked = 0;
        for (ChecklistItem item : items) {
            if (destination.getId().equals(item.getDestinationId())) {
                item.setDestinationId(null);
                unlinked++;
            }
        }
        if (unlinked > 0) checklist.replaceAll(trip.getSlug(), items);
        return unlinked;
    }

    public void reorder(String tripId, String userId, List<String> orderedIds) {
        Trip trip = access.requireMember(tripId, userId);
        List<Destination> all = new ArrayList<>(destinations.findAll(trip.getSlug()));
        for (Destination destination : all) {
            int position = orderedIds.indexOf(destination.getId());
            if (position >= 0) destination.setSortOrder(position);
        }
        destinations.replaceAll(trip.getSlug(), all);
    }

    private void apply(Destination destination, Input input) {
        if (input.name() != null) destination.setName(input.name().trim());
        if (input.startDate() != null) destination.setStartDate(input.startDate());
        if (input.endDate() != null) destination.setEndDate(input.endDate());
        if (input.notes() != null) destination.setNotes(blankToNull(input.notes()));
        if (input.geonameId() != null) destination.setGeonameId(input.geonameId());
        if (input.timezone() != null) destination.setTimezone(blankToNull(input.timezone()));

        // Coordinates are always accepted as given, so a member can correct a
        // bad match or fill them in for a place the gazetteer does not know.
        if (input.latitude() != null) destination.setLatitude(validLatitude(input.latitude()));
        if (input.longitude() != null) destination.setLongitude(validLongitude(input.longitude()));

        if (input.countryCode() != null && !input.countryCode().isBlank()) {
            String code = input.countryCode().trim().toUpperCase();
            destination.setCountryCode(code);
            destination.setCountryName(
                    input.countryName() != null && !input.countryName().isBlank()
                            ? input.countryName().trim()
                            : countries.nameOf(code));
            destination.setCountryFlag(countries.flagOf(code));
        } else if (input.countryName() != null) {
            destination.setCountryName(blankToNull(input.countryName()));
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("A destination needs a name.");
        }
        if (name.trim().length() > 120) {
            throw ApiException.badRequest("That destination name is too long.");
        }
    }

    private static void requireDateOrder(LocalDate start, LocalDate end) {
        // Both are optional; only their order is checked.
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.badRequest("The end date cannot be before the start date.");
        }
    }

    private static Double validLatitude(double value) {
        if (value < -90 || value > 90) {
            throw ApiException.badRequest("Latitude must be between -90 and 90.");
        }
        return value;
    }

    private static Double validLongitude(double value) {
        if (value < -180 || value > 180) {
            throw ApiException.badRequest("Longitude must be between -180 and 180.");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
