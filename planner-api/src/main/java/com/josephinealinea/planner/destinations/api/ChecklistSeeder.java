package com.josephinealinea.planner.destinations.api;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.shared.Ids;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Adding a destination seeds the three things every place needs planning for.
 *
 * The accommodation line counts nights when both dates are known — Cusco
 * 25-Oct to 31-Oct becomes "Plan 6N accommodation in Cusco" — and falls back to
 * the plain wording when it is not calculable, because both dates are optional.
 *
 * These are ordinary checklist items once created. Nothing re-derives them, so
 * renaming the destination later does not overwrite text a member has edited.
 */
@Component
public class ChecklistSeeder {

    public List<ChecklistItem> seedFor(Destination destination, int startingSortOrder) {
        String name = destination.getName();
        Long nights = destination.nights();

        String accommodation = nights == null
                ? "Plan accommodation in %s".formatted(name)
                : "Plan %dN accommodation in %s".formatted(nights, name);

        return List.of(
                item(destination, ChecklistCategory.TRANSPORTATION,
                        "Plan transportation to %s".formatted(name), startingSortOrder),
                item(destination, ChecklistCategory.LODGING,
                        accommodation, startingSortOrder + 1),
                item(destination, ChecklistCategory.ACTIVITIES,
                        "Plan activities in %s".formatted(name), startingSortOrder + 2));
    }

    private ChecklistItem item(Destination destination,
                               ChecklistCategory category,
                               String description,
                               int sortOrder) {
        ChecklistItem item = new ChecklistItem();
        item.setId(Ids.newId());
        item.setTripId(destination.getTripId());
        item.setDestinationId(destination.getId());
        item.setCategory(category);
        item.setDescription(description);
        item.setAutoSeeded(true);
        item.setSortOrder(sortOrder);
        item.setCreatedAt(Instant.now());
        return item;
    }
}
