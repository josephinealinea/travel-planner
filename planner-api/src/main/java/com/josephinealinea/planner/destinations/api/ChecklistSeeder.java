package com.josephinealinea.planner.destinations.api;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.i18n.I18nConfig;
import com.josephinealinea.planner.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Adding a destination seeds the things that place needs planning for.
 *
 * Transport and activities always apply. Accommodation only does when the dates
 * say somebody sleeps there: `Nights.between` (via Destination.nights) is null
 * unless both dates exist and the end is after the start, so a destination with
 * no dates yet, or a day trip like 24-Oct to 24-Oct, seeds two items rather
 * than three. There is nothing to book for a day you do not stay over.
 *
 * The accommodation line counts those nights — Cusco 25-Oct to 31-Oct becomes
 * "Plan 6N accommodation in Cusco" — which is only ever written when a count
 * exists, so the old un-numbered fallback wording is gone with it.
 *
 * When dates are added later, DestinationService.update seeds the missing
 * accommodation item then. It does that once and only once, tracked on the
 * destination, so an item somebody deleted on purpose does not come back the
 * next time the dates are touched.
 *
 * These are ordinary checklist items once created. Nothing re-derives them, so
 * renaming the destination later does not overwrite text a member has edited.
 */
@Component
public class ChecklistSeeder {

    private final Messages messages;

    @Autowired
    public ChecklistSeeder(Messages messages) {
        this.messages = messages;
    }

    /** English, for tests that build the seeder by hand. */
    public ChecklistSeeder() {
        this(I18nConfig.standalone());
    }

    public List<ChecklistItem> seedFor(Destination destination, int startingSortOrder) {
        String name = destination.getName();
        List<ChecklistItem> seeded = new ArrayList<>();

        seeded.add(item(destination, ChecklistCategory.TRANSPORTATION,
                messages.get("seed.transportation", name), startingSortOrder));
        if (needsAccommodation(destination)) {
            seeded.add(accommodationFor(destination, startingSortOrder + seeded.size()));
        }
        seeded.add(item(destination, ChecklistCategory.ACTIVITIES,
                messages.get("seed.activities", name), startingSortOrder + seeded.size()));
        return List.copyOf(seeded);
    }

    /** True when the dates cover at least one night somebody has to sleep. */
    public boolean needsAccommodation(Destination destination) {
        return destination.nights() != null;
    }

    /** The accommodation line on its own, for dates that arrive later. */
    public ChecklistItem accommodationFor(Destination destination, int sortOrder) {
        return item(destination, ChecklistCategory.LODGING,
                messages.get("seed.accommodation", String.valueOf(destination.nights()), destination.getName()),
                sortOrder);
    }

    private ChecklistItem item(Destination destination,
                               ChecklistCategory category,
                               String description,
                               int sortOrder) {
        ChecklistItem item = new ChecklistItem();
        item.setId(Ids.newId());
        item.setTripId(destination.getTripId());
        // The link a member sees and filters by is the country. The city is
        // kept alongside it as provenance: PlanTemplates needs it to suggest a
        // name and the stay's own dates, and the Destinations table counts the
        // items each city produced. A destination with no country simply
        // contributes no link.
        if (destination.getCountryCode() != null && !destination.getCountryCode().isBlank()) {
            item.setCountryCodes(List.of(destination.getCountryCode().trim().toUpperCase()));
        }
        item.setSeededFromDestinationId(destination.getId());
        item.setCategory(category);
        item.setDescription(description);
        item.setAutoSeeded(true);
        item.setSortOrder(sortOrder);
        item.setCreatedAt(Instant.now());
        return item;
    }
}
