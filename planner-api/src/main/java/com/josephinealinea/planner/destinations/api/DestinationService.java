package com.josephinealinea.planner.destinations.api;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.Travellers;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripWindow;
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
            String notes,
            /** Null on a patch means "leave it as it is". */
            Boolean suppressChecklist,
            /** Who's going. Null leaves it alone; [] is the whole trip. See Travellers. */
            List<String> travellerIds,
            /** True clears it back to "not set". */
            Boolean inheritTravellers) {

        /** The shape from before travellers existed: says nothing about them. */
        public Input(String name, String countryCode, String countryName, Double latitude,
                     Double longitude, Long geonameId, String timezone, LocalDate startDate,
                     LocalDate endDate, String notes, Boolean suppressChecklist) {
            this(name, countryCode, countryName, latitude, longitude, geonameId, timezone,
                    startDate, endDate, notes, suppressChecklist, null, null);
        }
    }

    /** Creating one also produces its three checklist items. */
    public record Created(Destination destination, List<ChecklistItem> seededChecklist) {}

    /**
     * How many checklist items, itinerary entries and budget rows had this
     * destination removed from their links — each count on its own, since
     * these are three different collections and conflating them would stop
     * meaning any one thing precisely.
     */
    public record UnlinkResult(int checklistItemsUnlinked, int itineraryItemsUnlinked, int budgetItemsUnlinked) {}

    private final DestinationRepository destinations;
    private final ChecklistRepository checklist;
    private final ItineraryRepository itinerary;
    private final BudgetRepository budget;
    private final ChecklistSeeder seeder;
    private final TripAccessService access;
    private final CountryCatalog countries;
    private final TripCountries tripCountries;

    public DestinationService(DestinationRepository destinations,
                              ChecklistRepository checklist,
                              ItineraryRepository itinerary,
                              BudgetRepository budget,
                              ChecklistSeeder seeder,
                              TripAccessService access,
                              CountryCatalog countries,
                              TripCountries tripCountries) {
        this.destinations = destinations;
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.budget = budget;
        this.seeder = seeder;
        this.access = access;
        this.countries = countries;
        this.tripCountries = tripCountries;
    }

    public List<Destination> list(String tripId, String userId) {
        Trip trip = access.requireMember(tripId, userId);
        return destinations.findAllOrdered(trip.getSlug());
    }

    public Created create(String tripId, String userId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        requireName(input.name());
        requireDateOrder(input.startDate(), input.endDate());
        requireWithinTrip(trip, input);

        Destination destination = new Destination();
        destination.setId(Ids.newId());
        destination.setTripId(tripId);
        destination.setCreatedAt(Instant.now());
        destination.setSortOrder(destinations.findAll(trip.getSlug()).size());
        apply(destination, input);
        destination.setTravellerIds(Travellers.change(trip, null,
                input.travellerIds(), input.inheritTravellers()));
        Audit.created(destination, userId);
        destinations.save(trip.getSlug(), destination);

        // Asked for on the form and remembered on the destination, because the
        // accommodation item is also seeded later — see
        // seedLodgingIfTheDatesNowNeedIt, which honours the same flag.
        if (destination.seedsNoChecklist()) {
            return new Created(destination, List.of());
        }

        int nextSortOrder = checklist.findAll(trip.getSlug()).size();
        List<ChecklistItem> seeded = seeder.seedFor(destination, nextSortOrder);
        // Seeded by the app, but on behalf of whoever added the destination —
        // which is the answer somebody reading the file back actually wants.
        Audit.allCreated(seeded, userId);
        checklist.saveAll(trip.getSlug(), seeded);

        // Only true if the dates already covered a night. Somewhere to sleep
        // is the one seeded item the dates decide, and they often arrive after
        // the destination does — see update.
        if (seeder.needsAccommodation(destination)) {
            destination.setLodgingSeeded(true);
            destinations.save(trip.getSlug(), destination);
        }

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
        requireWithinTrip(trip, input);

        apply(destination, input);
        destination.setTravellerIds(Travellers.change(trip, destination.getTravellerIds(),
                input.travellerIds(), input.inheritTravellers()));
        Audit.touched(destination, userId);
        seedLodgingIfTheDatesNowNeedIt(trip, userId, destination);
        // Deliberately does not rewrite the seeded checklist text — once created
        // those descriptions belong to whoever has been editing them.
        return destinations.save(trip.getSlug(), destination);
    }

    /**
     * Deleting a destination unlinks its checklist items, itinerary entries
     * and budget rows rather than deleting them. Losing a place should never
     * silently discard the planning done against it, including any plans and
     * costs hanging off those items.
     *
     * Since those links are countries, most deletions now unlink nothing at
     * all: dropping Cusco leaves a Peru-linked item exactly as it was, because
     * the trip still goes to Peru via Ollantaytambo. Only losing the last
     * destination in a country detaches anything, which is the only point at
     * which the link stopped being true.
     */
    public UnlinkResult delete(String tripId, String userId, String destinationId) {
        Trip trip = access.requireMember(tripId, userId);
        Destination destination = destinations.findById(trip.getSlug(), destinationId)
                .orElseThrow(() -> ApiException.notFound("Destination"));

        destinations.delete(trip.getSlug(), destinationId);

        String orphaned = orphanedCountry(trip, destination);
        if (orphaned == null) return new UnlinkResult(0, 0, 0);

        List<ChecklistItem> checklistItems = new ArrayList<>(checklist.findAll(trip.getSlug()));
        int checklistUnlinked = 0;
        for (ChecklistItem item : checklistItems) {
            if (item.getCountryCodes().remove(orphaned)) {
                Audit.touched(item, userId);
                checklistUnlinked++;
            }
        }
        if (checklistUnlinked > 0) checklist.replaceAll(trip.getSlug(), checklistItems);

        List<ItineraryItem> itineraryItems = new ArrayList<>(itinerary.findAll(trip.getSlug()));
        int itineraryUnlinked = 0;
        for (ItineraryItem item : itineraryItems) {
            if (item.getCountryCodes().remove(orphaned)) {
                Audit.touched(item, userId);
                itineraryUnlinked++;
            }
        }
        if (itineraryUnlinked > 0) itinerary.replaceAll(trip.getSlug(), itineraryItems);

        List<BudgetItem> budgetItems = new ArrayList<>(budget.findAll(trip.getSlug()));
        int budgetUnlinked = 0;
        for (BudgetItem item : budgetItems) {
            if (item.getCountryCodes().remove(orphaned)) {
                Audit.touched(item, userId);
                budgetUnlinked++;
            }
        }
        if (budgetUnlinked > 0) budget.replaceAll(trip.getSlug(), budgetItems);

        return new UnlinkResult(checklistUnlinked, itineraryUnlinked, budgetUnlinked);
    }

    /**
     * Adds the accommodation item when an edit makes the dates cover a night.
     *
     * A destination is often saved before its dates are known, and a day trip
     * needs no room at all, so creating it may have seeded only transport and
     * activities. Filling the dates in later is the moment somewhere to sleep
     * becomes a real job, so that is when the item appears.
     *
     * Once per destination, ever. The flag is what makes it once: without it,
     * an item somebody deleted on purpose would reappear the next time anybody
     * touched the dates, and seeded items are ordinary items once created —
     * deleting one is a decision, not an accident to repair.
     *
     * Nothing is removed going the other way. Clearing the dates, or shrinking
     * a stay to a single day, leaves the item alone: it may already carry a
     * plan, a booking reference and a budget row, and none of that should
     * vanish because a date was corrected.
     */
    private void seedLodgingIfTheDatesNowNeedIt(Trip trip, String userId, Destination destination) {
        // The whole point of storing the flag: without this, suppressing the
        // checklist and then filling in the dates would produce the one item
        // the member explicitly said they did not want.
        if (destination.seedsNoChecklist()) return;
        if (destination.hasSeededLodging()) return;
        if (!seeder.needsAccommodation(destination)) return;

        int nextSortOrder = checklist.findAll(trip.getSlug()).size();
        ChecklistItem lodging = seeder.accommodationFor(destination, nextSortOrder);
        // Created by whoever's edit made the dates cover a night, not by
        // whoever added the destination back when it had no dates at all.
        Audit.created(lodging, userId);
        checklist.saveAll(trip.getSlug(), List.of(lodging));
        destination.setLodgingSeeded(true);
    }

    /**
     * The country the deleted destination took with it, or null if the trip
     * still visits it through another destination.
     */
    private String orphanedCountry(Trip trip, Destination deleted) {
        String code = tripCountries.codeOf(deleted);
        if (code == null) return null;
        boolean stillVisited = destinations.findAll(trip.getSlug()).stream()
                .anyMatch(remaining -> code.equals(tripCountries.codeOf(remaining)));
        return stillVisited ? null : code;
    }

    public void reorder(String tripId, String userId, List<String> orderedIds) {
        Trip trip = access.requireMember(tripId, userId);
        List<Destination> all = new ArrayList<>(destinations.findAll(trip.getSlug()));
        for (Destination destination : all) {
            int position = orderedIds.indexOf(destination.getId());
            if (position < 0 || position == destination.getSortOrder()) continue;
            destination.setSortOrder(position);
            Audit.touched(destination, userId);
        }
        destinations.replaceAll(trip.getSlug(), all);
    }

    private void apply(Destination destination, Input input) {
        if (input.name() != null) destination.setName(input.name().trim());
        // Absent leaves it alone; false clears it back to absent rather than
        // writing "suppressChecklist: false" into every destination.
        if (input.suppressChecklist() != null) {
            destination.setSuppressChecklist(input.suppressChecklist() ? true : null);
        }
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

    /**
     * Only the dates actually sent are checked, so an older destination that
     * predates a change to the trip's own dates stays editable.
     */
    private static void requireWithinTrip(Trip trip, Input input) {
        TripWindow window = TripWindow.of(trip);
        window.require(input.startDate(), "start date");
        window.require(input.endDate(), "end date");
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
