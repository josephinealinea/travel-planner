package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a trip member is named on the trip's own records.
 *
 * "Linked" means an explicit link: a member picked as Shared by or Paid by on
 * an expense, or listed as a traveller on a destination, checklist item or
 * plan. A record that merely inherits the whole trip names nobody, so it does
 * not count.
 */
@Component
public class MemberLinks {

    private final BudgetRepository budget;
    private final DestinationRepository destinations;
    private final ChecklistRepository checklist;
    private final ItineraryRepository itinerary;

    public MemberLinks(BudgetRepository budget,
                       DestinationRepository destinations,
                       ChecklistRepository checklist,
                       ItineraryRepository itinerary) {
        this.budget = budget;
        this.destinations = destinations;
        this.checklist = checklist;
        this.itinerary = itinerary;
    }

    /** Whether the member shares or paid for any expense on the trip. */
    public boolean inBudget(String slug, String userId) {
        return budget.findAll(slug).stream().anyMatch(item -> mentions(item, userId));
    }

    /** The parts of the trip that name this member, in the order the tabs appear. */
    public List<String> areasLinkedTo(String slug, String userId) {
        List<String> areas = new ArrayList<>();
        if (destinations.findAll(slug).stream().anyMatch(d -> contains(d.getTravellerIds(), userId))) {
            areas.add("destinations");
        }
        if (checklist.findAll(slug).stream().anyMatch(c -> contains(c.getTravellerIds(), userId))) {
            areas.add("checklist");
        }
        if (itinerary.findAll(slug).stream().anyMatch(i -> contains(i.getTravellerIds(), userId))) {
            areas.add("itinerary");
        }
        if (inBudget(slug, userId)) areas.add("budget");
        return areas;
    }

    /**
     * Points every expense that names {@code from} at {@code to} instead, so a
     * member who leaves keeps their share and what they paid under the
     * placeholder that stands in for them.
     */
    public void moveBudgetLinks(String slug, String from, String to) {
        List<BudgetItem> items = budget.findAll(slug);
        for (BudgetItem item : items) {
            if (!mentions(item, from) && !from.equals(item.getCreatedByUserId())) continue;
            item.setSharedByUserIds(item.getSharedByUserIds().stream()
                    .map(id -> id.equals(from) ? to : id).toList());
            if (from.equals(item.getPaidByUserId())) item.setPaidByUserId(to);
            if (from.equals(item.getCreatedByUserId())) item.setCreatedByUserId(to);
        }
        budget.replaceAll(slug, items);
    }

    private static boolean mentions(BudgetItem item, String userId) {
        return contains(item.getSharedByUserIds(), userId) || userId.equals(item.getPaidByUserId());
    }

    private static boolean contains(List<String> ids, String userId) {
        return ids != null && ids.contains(userId);
    }
}
