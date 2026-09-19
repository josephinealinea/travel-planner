package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.trips.domain.Trip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Who is going to which part of a trip, worked out in one place.
 *
 * Destinations, checklist items and plans each carry an optional
 * travellerIds. Null means "not set: same as the parent", so choosing who goes
 * to Cusco once covers its seeded checklist and the plans made from it, and a
 * later change to Cusco carries down to everything nobody has edited. An empty
 * list is explicitly the whole trip. The chain is destination, then checklist
 * item (via seededFromDestinationId), then plan (via checklistItemId); a
 * stay's later days always follow their plan's own row, so a booking cannot
 * be half yours.
 *
 * <b>An empty result means the whole trip</b>, for the reason TripMembers
 * gives: buddies who have left drop out, and a record whose named travellers
 * have all gone must not become visible to nobody.
 *
 * Static and built from lists the caller already holds, like TripMembers: no
 * repository calls of its own. The page never re-derives any of this; it is
 * sent `mine` and `named` instead, so the planner and a personal published
 * page cannot disagree.
 */
public final class Travellers {

    /** What one buddy's "Mine" view shows, in the order given. */
    public record Mine(List<String> destinationIds,
                       List<String> checklistItemIds,
                       List<String> itineraryItemIds) {}

    /** Resolved lists for display, only where they are not the whole trip. */
    public record Named(Map<String, List<String>> destinations,
                        Map<String, List<String>> checklist,
                        Map<String, List<String>> itinerary) {}

    private final Set<String> members;
    private final Map<String, Destination> destinations = new LinkedHashMap<>();
    private final Map<String, ChecklistItem> checklist = new LinkedHashMap<>();
    private final Map<String, ItineraryItem> itinerary = new LinkedHashMap<>();

    private Travellers(Set<String> members) {
        this.members = members;
    }

    public static Travellers of(Trip trip,
                                List<Destination> destinations,
                                List<ChecklistItem> checklist,
                                List<ItineraryItem> itinerary) {
        Travellers t = new Travellers(new LinkedHashSet<>(TripMembers.of(trip).userIds()));
        destinations.forEach(d -> t.destinations.put(d.getId(), d));
        checklist.forEach(c -> t.checklist.put(c.getId(), c));
        itinerary.forEach(i -> t.itinerary.put(i.getId(), i));
        return t;
    }

    public List<String> ofDestination(Destination destination) {
        return normalise(destination.getTravellerIds());
    }

    public List<String> ofChecklistItem(ChecklistItem item) {
        if (item.getTravellerIds() != null) return normalise(item.getTravellerIds());
        String from = item.getSeededFromDestinationId();
        Destination destination = from == null ? null : destinations.get(from);
        // A deleted destination leaves its items following nothing: the whole
        // trip. Losing a place never hides the planning done against it.
        return destination == null ? List.of() : ofDestination(destination);
    }

    public List<String> ofItineraryItem(ItineraryItem entry) {
        if (entry.getPlanId() != null) {
            ItineraryItem plan = itinerary.get(entry.getPlanId());
            // A plan's own row has no planId; anything else is a hand-edited
            // file, which falls back to the whole trip rather than looping.
            return plan == null || plan.getPlanId() != null ? List.of() : ofItineraryItem(plan);
        }
        if (entry.getTravellerIds() != null) return normalise(entry.getTravellerIds());
        String itemId = entry.getChecklistItemId();
        ChecklistItem item = itemId == null ? null : checklist.get(itemId);
        return item == null ? List.of() : ofChecklistItem(item);
    }

    /** True when a resolved list is the whole trip or names this buddy. */
    public static boolean includes(List<String> resolved, String userId) {
        return resolved.isEmpty() || resolved.contains(userId);
    }

    public Mine mineFor(String userId) {
        return new Mine(
                idsWhere(destinations, d -> includes(ofDestination(d), userId)),
                idsWhere(checklist, c -> includes(ofChecklistItem(c), userId)),
                idsWhere(itinerary, i -> includes(ofItineraryItem(i), userId)));
    }

    public Named named() {
        return new Named(
                namedOnly(destinations, this::ofDestination),
                namedOnly(checklist, this::ofChecklistItem),
                namedOnly(itinerary, this::ofItineraryItem));
    }

    /**
     * What to store after a request. inherit=true clears the list back to
     * "follow the parent" and wins over anything else sent; a null list leaves
     * what is stored alone; any other list, an empty one included, is
     * validated against the trip's buddies and stored as sent.
     */
    public static List<String> change(Trip trip, List<String> stored, List<String> wanted, Boolean inherit) {
        if (Boolean.TRUE.equals(inherit)) return null;
        if (wanted == null) return stored;
        return TripMembers.of(trip).validate(wanted);
    }

    /** True when a request says anything at all about travellers. */
    public static boolean changes(List<String> wanted, Boolean inherit) {
        return wanted != null || Boolean.TRUE.equals(inherit);
    }

    private List<String> normalise(List<String> ids) {
        if (ids == null) return List.of();
        List<String> kept = new ArrayList<>();
        for (String id : ids) {
            if (id != null && members.contains(id) && !kept.contains(id)) kept.add(id);
        }
        return List.copyOf(kept);
    }

    private static <T> List<String> idsWhere(Map<String, T> byId, Predicate<T> test) {
        List<String> ids = new ArrayList<>();
        byId.forEach((id, value) -> {
            if (test.test(value)) ids.add(id);
        });
        return ids;
    }

    private static <T> Map<String, List<String>> namedOnly(Map<String, T> byId, Function<T, List<String>> resolve) {
        Map<String, List<String>> named = new LinkedHashMap<>();
        byId.forEach((id, value) -> {
            List<String> resolved = resolve.apply(value);
            if (!resolved.isEmpty()) named.put(id, resolved);
        });
        return named;
    }
}
